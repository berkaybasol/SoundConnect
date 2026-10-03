package com.berkayb.soundconnect.modules.profile.shared.resolver.contributor;

import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.profile.OrganizerProfile.entity.OrganizerProfile;
import com.berkayb.soundconnect.modules.profile.OrganizerProfile.repository.OrganizerProfileRepository;
import com.berkayb.soundconnect.modules.profile.ProducerProfile.entity.ProducerProfile;
import com.berkayb.soundconnect.modules.profile.ProducerProfile.repository.ProducerProfileRepository;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.support.ListenerVisibilityPolicy;
import com.berkayb.soundconnect.modules.profile.shared.BaseProfile;
import com.berkayb.soundconnect.modules.profile.shared.resolver.service.PublicProfileResolverServiceImpl;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class OrganizerProducerProfileContributorTest {
    record Fixture(UUID userId, UUID profileId, UUID avatarId, User user, BaseProfile profile,
                   MediaAssetService media, PublicProfileContributor contributor) { }

    private Fixture fixture(String type) {
        UUID userId = UUID.randomUUID(), profileId = UUID.randomUUID(), avatarId = UUID.randomUUID();
        User user = User.builder().id(userId).username("publichandle").status(UserStatus.ACTIVE)
                .emailVerified(true).profilePicture("https://private.example/never-use.jpg").build();
        MediaAssetService media = mock(MediaAssetService.class);
        BaseProfile profile;
        PublicProfileContributor contributor;
        if (type.equals("ORGANIZER")) {
            var repository = mock(OrganizerProfileRepository.class);
            profile = OrganizerProfile.builder().id(profileId).user(user).name("Private profile copy")
                    .profilePictureMediaId(avatarId).build();
            when(repository.findByUserId(userId)).thenReturn(Optional.of((OrganizerProfile) profile));
            contributor = new OrganizerProfileContributor(repository, media);
        } else {
            var repository = mock(ProducerProfileRepository.class);
            profile = ProducerProfile.builder().id(profileId).user(user).name("Private profile copy")
                    .profilePictureMediaId(avatarId).build();
            when(repository.findByUserId(userId)).thenReturn(Optional.of((ProducerProfile) profile));
            contributor = new ProducerProfileContributor(repository, media);
        }
        return new Fixture(userId, profileId, avatarId, user, profile, media, contributor);
    }

    @ParameterizedTest @ValueSource(strings={"ORGANIZER", "PRODUCER"})
    void activePublicIdentityUsesUsernameAndDisplayMedia(String type) {
        Fixture f = fixture(type);
        when(f.media.getDisplayUrl(f.avatarId)).thenReturn("https://cdn.example/public-avatar.jpg");
        assertThat(f.contributor.resolve(f.userId)).singleElement().satisfies(target -> {
            assertThat(target.type()).isEqualTo(type);
            assertThat(target.profileId()).isEqualTo(f.profileId);
            assertThat(target.displayName()).isEqualTo("publichandle");
            assertThat(target.profilePictureUrl()).isEqualTo("https://cdn.example/public-avatar.jpg");
        });
        verify(f.media).getDisplayUrl(f.avatarId);
        verifyNoMoreInteractions(f.media);
    }

    @ParameterizedTest @ValueSource(strings={"ORGANIZER", "PRODUCER"})
    void erasedInactivePendingOrUnverifiedAccountsHaveNoPublicIdentity(String type) {
        Fixture f = fixture(type);
        for (UserStatus status : UserStatus.values()) {
            if (status == UserStatus.ACTIVE) continue;
            f.user.setStatus(status);
            assertThat(f.contributor.resolve(f.userId)).isEmpty();
        }
        f.user.setStatus(UserStatus.ACTIVE);
        f.user.setErasedAt(LocalDateTime.of(2026, 9, 23, 0, 0));
        assertThat(f.contributor.resolve(f.userId)).isEmpty();
        f.user.setErasedAt(null);
        f.user.setEmailVerified(false);
        assertThat(f.contributor.resolve(f.userId)).isEmpty();
        f.user.setEmailVerified(null);
        assertThat(f.contributor.resolve(f.userId)).isEmpty();
        verifyNoInteractions(f.media);
    }

    @ParameterizedTest @ValueSource(strings={"ORGANIZER", "PRODUCER"})
    void missingAndMismatchedOwnerFailClosed(String type) {
        Fixture f = fixture(type);
        f.profile.setUser(null);
        assertThat(f.contributor.resolve(f.userId)).isEmpty();
        f.user.setId(UUID.randomUUID());
        f.profile.setUser(f.user);
        assertThat(f.contributor.resolve(f.userId)).isEmpty();
        assertThat(f.contributor.resolve(null)).isEmpty();
        verifyNoInteractions(f.media);
    }

    @ParameterizedTest @ValueSource(strings={"ORGANIZER", "PRODUCER"})
    void missingOrUnavailableMediaNeverFallsBackToRawAccountImage(String type) {
        Fixture f = fixture(type);
        f.profile.setProfilePictureMediaId(null);
        assertThat(f.contributor.resolve(f.userId)).singleElement()
                .satisfies(target -> assertThat(target.profilePictureUrl()).isNull());
        verifyNoInteractions(f.media);
        f.profile.setProfilePictureMediaId(f.avatarId);
        when(f.media.getDisplayUrl(f.avatarId)).thenThrow(new IllegalStateException("fixture"));
        assertThat(f.contributor.resolve(f.userId)).singleElement()
                .satisfies(target -> assertThat(target.profilePictureUrl()).isNull());
    }

    @ParameterizedTest @ValueSource(strings={"ORGANIZER", "PRODUCER"})
    void publicResolverStillSuppressesPendingAndGhostAlternateProfiles(String type) {
        Fixture f = fixture(type);
        var visibility = mock(ListenerVisibilityPolicy.class);
        var resolver = new PublicProfileResolverServiceImpl(List.of(f.contributor), visibility);
        when(visibility.publicVisibilityRestrictions(List.of(f.userId)))
                .thenReturn(new ListenerVisibilityPolicy.PublicVisibilityRestrictions(Set.of(), Set.of(f.userId)));
        assertThat(resolver.resolveByUserId(f.userId).profiles()).isEmpty();
        verifyNoInteractions(f.media);
        when(visibility.publicVisibilityRestrictions(List.of(f.userId)))
                .thenReturn(new ListenerVisibilityPolicy.PublicVisibilityRestrictions(Set.of(f.userId), Set.of()));
        assertThat(resolver.resolveByUserId(f.userId).profiles()).isEmpty();
    }
}
