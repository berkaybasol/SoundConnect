package com.berkayb.soundconnect.modules.profile.MusicianProfile.service;

import com.berkayb.soundconnect.modules.instrument.entity.Instrument;
import com.berkayb.soundconnect.modules.instrument.repository.InstrumentRepository;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.service.BandService;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.dto.request.MusicianProfileSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.mapper.MusicianProfileMapper;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.repository.MusicianProfileRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.repository.MusicianProfileVenueRow;
import com.berkayb.soundconnect.modules.profile.shared.type.PersonalProfileTypePolicy;
import com.berkayb.soundconnect.modules.role.enums.RoleEnum;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.support.UserEntityFinder;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import jakarta.persistence.EntityManager;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MusicianProfileContentRegressionTest {
    @Mock MusicianProfileRepository profiles;
    @Mock PersonalProfileTypePolicy profilePolicy;
    @Mock UserEntityFinder users;
    @Mock InstrumentRepository instruments;
    @Mock BandService bands;
    @Mock MediaAssetService media;
    @Mock EntityManager entityManager;
    @Spy MusicianProfileMapper mapper = Mappers.getMapper(MusicianProfileMapper.class);
    @InjectMocks MusicianProfileServiceImpl service;
    final UUID userId = UUID.randomUUID();
    final User user = User.builder().id(userId).username("artist").build();

    @Test void creationPersistsAndReturnsTheSelectedSpotifyTrackIds() {
        when(profilePolicy.lockAndAssertCanAcquire(userId, RoleEnum.ROLE_MUSICIAN)).thenReturn(user);
        when(profiles.save(any())).thenAnswer(invocation -> {
            MusicianProfile profile = invocation.getArgument(0);
            profile.setId(UUID.randomUUID());
            return profile;
        });
        var dto = content(null, "artist", null, List.of("trackA", "trackB"));

        var response = service.createProfile(userId, dto);

        assertThat(response.spotifyTrackIds()).containsExactly("trackA", "trackB");
        var persisted = ArgumentCaptor.forClass(MusicianProfile.class);
        verify(profiles).save(persisted.capture());
        assertThat(persisted.getValue().getSpotifyTrackIds()).containsExactly("trackA", "trackB");
    }

    @Test void missingInstrumentRejectsTheWholeCreateInsteadOfSilentlyDroppingIt() {
        UUID existing = UUID.randomUUID(), missing = UUID.randomUUID();
        when(profilePolicy.lockAndAssertCanAcquire(userId, RoleEnum.ROLE_MUSICIAN)).thenReturn(user);
        when(instruments.findAllById(Set.of(existing, missing)))
                .thenReturn(List.of(Instrument.builder().name("Guitar").build()));

        assertThatThrownBy(() -> service.createProfile(userId, content(null, null, Set.of(existing, missing), null)))
                .isInstanceOfSatisfying(SoundConnectException.class, exception ->
                        assertThat(exception.getErrorType()).isEqualTo(ErrorType.INSTRUMENT_NOT_FOUND));
        verify(profiles, never()).save(any());
    }

    @Test void blankArtistIdClearsToNullAndOmittedFieldsArePreservedUnderTheWriteLock() {
        MusicianProfile current = MusicianProfile.builder().id(UUID.randomUUID()).user(user)
                .description("Keep this bio").spotifyArtistId("oldArtist")
                .instagramUrl("https://instagram.com/old").build();
        when(users.getUser(userId)).thenReturn(user);
        when(profiles.findByUserIdForUpdate(userId)).thenReturn(Optional.of(current));
        when(profiles.save(current)).thenReturn(current);

        service.updateProfile(userId, content("", " ", null, null));

        assertThat(current.getDescription()).isEqualTo("Keep this bio");
        assertThat(current.getSpotifyArtistId()).isNull();
        assertThat(current.getInstagramUrl()).isEmpty();
        verify(profiles, never()).findByUserId(userId);
    }

    @Test void unsafeLinkAndOversizedContentFailBeforeAProfileIsWritten() {
        assertThatThrownBy(() -> service.updateProfile(userId, content("javascript:alert(1)", null, null, null)))
                .isInstanceOf(SoundConnectException.class);
        var oversized = new MusicianProfileSaveRequestDto(null, "x".repeat(1025), null,
                null, null, null, null, null, null, null, null);
        assertThatThrownBy(() -> service.createProfile(userId, oversized)).isInstanceOf(SoundConnectException.class);
        verifyNoInteractions(profiles, profilePolicy, users);
    }

    @Test void builderDefaultsAreEmptyCollectionsForProvisionedProfiles() {
        MusicianProfile profile = MusicianProfile.builder().user(user).build();
        assertThat(profile.getSpotifyTrackIds()).isEmpty();
        assertThat(profile.getSpotifyTracks()).isEmpty();
    }

    @Test void publicNamesAndCardsUseOnlyEligibleVenueRowsWithOneMediaBatch() {
        UUID profileId = UUID.randomUUID(), visibleId = UUID.randomUUID(), hiddenId = UUID.randomUUID();
        UUID visibleImage = UUID.randomUUID();
        MusicianProfile profile = MusicianProfile.builder().id(profileId).user(user)
                .activeVenues(Set.of(Venue.builder().id(visibleId).name("Visible venue").build(),
                        Venue.builder().id(hiddenId).name("Hidden venue").build())).build();
        when(profiles.findById(profileId)).thenReturn(Optional.of(profile));
        when(profiles.findPublicVenueConnections(profileId)).thenReturn(List.of(
                new MusicianProfileVenueRow(visibleId, "Visible venue", visibleImage)));
        when(media.getDisplayUrlMap(List.of(visibleImage))).thenReturn(Map.of(visibleImage, "https://cdn.test/visible"));

        var result = service.getProfileByProfileId(profileId);

        assertThat(result.activeVenues()).containsExactly("Visible venue");
        assertThat(result.activeVenueConnections()).singleElement().satisfies(connection -> {
            assertThat(connection.venueId()).isEqualTo(visibleId);
            assertThat(connection.profileImageUrl()).isEqualTo("https://cdn.test/visible");
        });
        verify(profiles).findPublicVenueConnections(profileId);
        verify(media).getDisplayUrlMap(List.of(visibleImage));
        verifyNoMoreInteractions(media);
    }

    private MusicianProfileSaveRequestDto content(String instagram, String spotifyArtistId,
                                                   Set<UUID> ids, List<String> tracks) {
        return new MusicianProfileSaveRequestDto(null, null, null, instagram, null, null,
                null, spotifyArtistId, ids, tracks, null);
    }
}
