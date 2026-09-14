package com.berkayb.soundconnect.modules.feed.musician.core;

import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.repository.MusicianProfileRepository;
import com.berkayb.soundconnect.modules.profile.VenueProfile.entity.VenueProfile;
import com.berkayb.soundconnect.modules.profile.VenueProfile.repository.VenueProfileRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import com.berkayb.soundconnect.modules.venue.enums.VenueStatus;
import com.berkayb.soundconnect.modules.venue.repository.VenueRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MusicianFeedViewerGuardTest {
    private final UserRepository users = mock(UserRepository.class);
    private final MusicianProfileRepository musicians = mock(MusicianProfileRepository.class);
    private final VenueRepository venues = mock(VenueRepository.class);
    private final VenueProfileRepository venueProfiles = mock(VenueProfileRepository.class);
    private final MusicianFeedViewerGuard guard = new MusicianFeedViewerGuard(users, musicians, venues, venueProfiles);
    private final UUID viewer = UUID.randomUUID();

    @Test
    void venueRequiresCanonicalDatabaseRoleAndActualAggregate() {
        when(users.findRoleNamesByUserId(viewer)).thenReturn(Set.of("ROLE_VENUE"));
        when(users.findExistingPersonalProfileRoleNames(viewer)).thenReturn(Set.of());
        assertForbidden(() -> guard.requireVenueProfile(viewer));
        when(users.findExistingPersonalProfileRoleNames(viewer)).thenReturn(Set.of("ROLE_VENUE", "ROLE_MUSICIAN"));
        assertForbidden(() -> guard.requireVenueProfile(viewer));
        when(users.findExistingPersonalProfileRoleNames(viewer)).thenReturn(Set.of("ROLE_VENUE"));
        when(users.findRoleNamesByUserId(viewer)).thenReturn(Set.of("ROLE_VENUE", "ROLE_MUSICIAN"));
        assertForbidden(() -> guard.requireVenueProfile(viewer));
        verifyNoInteractions(venues, venueProfiles, musicians);
    }

    @Test
    void unrelatedAdministrativeRolesDoNotTurnOnePersonalProfileIntoAnAmbiguousAccount() {
        authorizeVenue();
        when(users.findRoleNamesByUserId(viewer)).thenReturn(Set.of("ROLE_VENUE", "ROLE_ADMIN", "ROLE_OWNER"));
        Venue venue = venue(UUID.randomUUID());
        when(venues.findAllPubliclyVisibleByOwnerId(viewer)).thenReturn(List.of(venue));
        when(venueProfiles.findByVenueId(venue.getId())).thenReturn(Optional.of(new VenueProfile()));
        assertThat(guard.requireProfile(viewer, BackstageFeedAudience.VENUE)).isEqualTo(venue.getId());
    }

    @Test
    void venueIdIsTheDeterministicFirstEligibleOwnedProfileRegardlessOfRepositoryOrder() {
        authorizeVenue();
        Venue first = venue(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        Venue missingProfile = venue(UUID.fromString("00000000-0000-0000-0000-000000000000"));
        Venue second = venue(UUID.fromString("00000000-0000-0000-0000-000000000002"));
        when(venues.findAllPubliclyVisibleByOwnerId(viewer)).thenReturn(List.of(second, first, missingProfile));
        VenueProfile profile = new VenueProfile();
        profile.setId(UUID.randomUUID());
        when(venueProfiles.findByVenueId(first.getId())).thenReturn(Optional.of(profile));
        assertThat(guard.requireVenueProfile(viewer)).isEqualTo(first.getId()).isNotEqualTo(profile.getId());
        verify(venueProfiles, never()).findByVenueId(second.getId());
        verifyNoInteractions(musicians);
    }

    @Test
    void noApprovedPublicVenueOrMissingProfileCannotReadOrMutateFeed() {
        authorizeVenue();
        when(venues.findAllPubliclyVisibleByOwnerId(viewer)).thenReturn(List.of());
        assertForbidden(() -> guard.requireVenueProfile(viewer));
        when(venues.findAllPubliclyVisibleByOwnerId(viewer)).thenReturn(List.of(venue(UUID.randomUUID())));
        assertForbidden(() -> guard.requireVenueProfile(viewer));
    }

    @Test
    void erasedUnverifiedInactiveOrForeignOwnersAreRejectedEvenIfRepositoryReturnsThem() {
        authorizeVenue();
        Venue venue = venue(UUID.randomUUID());
        when(venues.findAllPubliclyVisibleByOwnerId(viewer)).thenReturn(List.of(venue));
        User owner = venue.getOwner();
        owner.setErasedAt(LocalDateTime.now());
        assertForbidden(() -> guard.requireVenueProfile(viewer));
        owner.setErasedAt(null);
        owner.setEmailVerified(false);
        assertForbidden(() -> guard.requireVenueProfile(viewer));
        owner.setEmailVerified(true);
        owner.setStatus(UserStatus.INACTIVE);
        assertForbidden(() -> guard.requireVenueProfile(viewer));
        owner.setStatus(UserStatus.ACTIVE);
        owner.setId(UUID.randomUUID());
        assertForbidden(() -> guard.requireVenueProfile(viewer));
        verifyNoInteractions(venueProfiles);
    }

    @Test
    void musicianAndVenueEndpointsDoNotAcceptTheOtherCanonicalRole() {
        authorizeVenue();
        assertForbidden(() -> guard.requireMusicianProfile(viewer));
        when(users.findRoleNamesByUserId(viewer)).thenReturn(Set.of("ROLE_MUSICIAN"));
        when(users.findExistingPersonalProfileRoleNames(viewer)).thenReturn(Set.of("ROLE_MUSICIAN"));
        assertForbidden(() -> guard.requireVenueProfile(viewer));
        MusicianProfile profile = new MusicianProfile();
        profile.setId(UUID.randomUUID());
        when(musicians.findByUserId(viewer)).thenReturn(Optional.of(profile));
        assertThat(guard.requireProfile(viewer, BackstageFeedAudience.MUSICIAN)).isEqualTo(profile.getId());
    }

    @Test
    void anonymousAndUnsupportedAudiencesFailBeforeDatabaseReads() {
        assertThatThrownBy(() -> guard.requireVenueProfile(null)).isInstanceOfSatisfying(
                SoundConnectException.class, failure -> assertThat(failure.getErrorType()).isEqualTo(ErrorType.UNAUTHORIZED));
        assertForbidden(() -> guard.requireProfile(viewer, null));
        verifyNoInteractions(users, musicians, venues, venueProfiles);
    }

    private void authorizeVenue() {
        when(users.findRoleNamesByUserId(viewer)).thenReturn(Set.of("ROLE_VENUE"));
        when(users.findExistingPersonalProfileRoleNames(viewer)).thenReturn(Set.of("ROLE_VENUE"));
    }

    private Venue venue(UUID id) {
        return Venue.builder().id(id).status(VenueStatus.APPROVED)
                .owner(User.builder().id(viewer).status(UserStatus.ACTIVE).emailVerified(true).build()).build();
    }

    private void assertForbidden(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(SoundConnectException.class,
                failure -> assertThat(failure.getErrorType()).isEqualTo(ErrorType.FORBIDDEN_ACCESS));
    }
}
