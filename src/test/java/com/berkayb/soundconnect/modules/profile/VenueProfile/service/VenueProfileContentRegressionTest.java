package com.berkayb.soundconnect.modules.profile.VenueProfile.service;

import com.berkayb.soundconnect.modules.profile.VenueProfile.dto.request.VenueProfileSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.VenueProfile.entity.VenueProfile;
import com.berkayb.soundconnect.modules.profile.VenueProfile.mapper.VenueProfileMapper;
import com.berkayb.soundconnect.modules.profile.VenueProfile.repository.VenueProfileRepository;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import com.berkayb.soundconnect.modules.venue.repository.VenueRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VenueProfileContentRegressionTest {
    @Mock VenueProfileRepository profiles;
    @Mock VenueRepository venues;
    @Spy VenueProfileMapper mapper = Mappers.getMapper(VenueProfileMapper.class);
    @InjectMocks VenueProfileServiceImpl service;

    @Test void ownerUpdateLocksTheProfileAndDistinguishesClearingFromOmission() {
        UUID ownerId = UUID.randomUUID(), venueId = UUID.randomUUID();
        Venue venue = Venue.builder().id(venueId).name("Venue").build();
        VenueProfile profile = VenueProfile.builder().venue(venue).bio("Old bio")
                .instagramUrl("https://instagram.com/old").youtubeUrl("https://youtube.com/keep").build();
        when(venues.findByIdAndOwnerId(venueId, ownerId)).thenReturn(Optional.of(venue));
        when(profiles.findByVenueIdForUpdate(venueId)).thenReturn(Optional.of(profile));
        when(profiles.save(profile)).thenReturn(profile);

        var result = service.updateProfileByVenueId(ownerId, venueId,
                new VenueProfileSaveRequestDto("", null, "", null, "venue.example.com"));

        assertThat(result.bio()).isEmpty();
        assertThat(result.instagramUrl()).isEmpty();
        assertThat(result.youtubeUrl()).isEqualTo("https://youtube.com/keep");
        assertThat(result.websiteUrl()).isEqualTo("https://venue.example.com");
        verify(profiles, never()).findByVenueId(any());
        var order = inOrder(venues, profiles);
        order.verify(venues).findByIdAndOwnerId(venueId, ownerId);
        order.verify(profiles).findByVenueIdForUpdate(venueId);
        order.verify(profiles).save(profile);
    }

    @Test void rejectsOversizedBioAndUnsafeLinksBeforeAnyWrite() {
        for (var invalid : new VenueProfileSaveRequestDto[]{
                new VenueProfileSaveRequestDto("x".repeat(1025), null, null, null, null),
                new VenueProfileSaveRequestDto(null, null, null, null, "data:text/html,payload")}) {
            assertThatThrownBy(() -> service.updateProfile(UUID.randomUUID(), invalid))
                    .isInstanceOfSatisfying(SoundConnectException.class, exception ->
                            assertThat(exception.getErrorType()).isEqualTo(ErrorType.VALIDATION_ERROR));
        }
        verifyNoInteractions(profiles, venues, mapper);
    }

    @Test void publicProfileFailsClosedBeforeReadingContentForAnIneligibleVenue() {
        UUID venueId = UUID.randomUUID();
        assertThatThrownBy(() -> service.getPublicProfileDetail(venueId))
                .isInstanceOfSatisfying(SoundConnectException.class, exception ->
                        assertThat(exception.getErrorType()).isEqualTo(ErrorType.VENUE_NOT_FOUND));
        verify(venues).findPubliclyVisibleById(venueId);
        verifyNoInteractions(profiles, mapper);
    }
}
