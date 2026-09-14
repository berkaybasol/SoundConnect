package com.berkayb.soundconnect.modules.feed.musician.core;

import com.berkayb.soundconnect.modules.profile.ListenerProfile.entity.ListenerProfile;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.repository.ListenerProfileRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.repository.MusicianProfileRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ListenerFeedViewerGuardTest {
    private final UserRepository users = mock(UserRepository.class);
    private final MusicianProfileRepository musicians = mock(MusicianProfileRepository.class);
    private final ListenerProfileRepository listeners = mock(ListenerProfileRepository.class);
    private final MusicianFeedViewerGuard guard = new MusicianFeedViewerGuard(users, musicians, null, null, listeners);
    private final UUID viewer = UUID.randomUUID();

    @ParameterizedTest
    @EnumSource(ListenerVisibilityMode.class)
    void listenerCanConsumeFeedWithoutPublishingTheirVisibilityOrChangingTheirMode(ListenerVisibilityMode mode) {
        var profile = authorize();
        profile.setVisibilityMode(mode);
        profile.setVisibilityChoiceCompleted(true);
        assertThat(guard.requireProfile(viewer, BackstageFeedAudience.LISTENER)).isEqualTo(profile.getId());
        assertThat(profile.getVisibilityMode()).isEqualTo(mode);
        assertThat(profile.isVisibilityChoiceCompleted()).isTrue();
        verifyNoInteractions(musicians);
    }

    @Test void pendingVisibilityChoiceDoesNotPublishAProfileThroughFeedConsumption() {
        var profile = authorize();
        profile.setVisibilityChoiceCompleted(false);
        assertThat(guard.requireListenerProfile(viewer)).isEqualTo(profile.getId());
        assertThat(profile.isPubliclyRestricted()).isTrue();
    }

    @Test void staleOrConflictingRolesAndAggregatesFailClosed() {
        authorize();
        when(users.findRoleNamesByUserId(viewer)).thenReturn(Set.of("ROLE_LISTENER", "ROLE_MUSICIAN"));
        forbidden(() -> guard.requireListenerProfile(viewer));
        when(users.findRoleNamesByUserId(viewer)).thenReturn(Set.of("ROLE_LISTENER"));
        when(users.findExistingPersonalProfileRoleNames(viewer)).thenReturn(Set.of("ROLE_LISTENER", "ROLE_VENUE"));
        forbidden(() -> guard.requireListenerProfile(viewer));
        when(users.findExistingPersonalProfileRoleNames(viewer)).thenReturn(Set.of());
        forbidden(() -> guard.requireListenerProfile(viewer));
        when(users.findExistingPersonalProfileRoleNames(viewer)).thenReturn(Set.of("ROLE_LISTENER"));
        when(users.findRoleNamesByUserId(viewer)).thenReturn(Set.of("ROLE_VENUE"));
        forbidden(() -> guard.requireListenerProfile(viewer));
    }

    @Test void inactiveUnverifiedErasedOrWrongOwnerCannotUseFeed() {
        var profile = authorize();
        var user = profile.getUser();
        user.setEmailVerified(false);
        forbidden(() -> guard.requireListenerProfile(viewer));
        user.setEmailVerified(true);
        user.setStatus(UserStatus.INACTIVE);
        forbidden(() -> guard.requireListenerProfile(viewer));
        user.setStatus(UserStatus.ACTIVE);
        user.setErasedAt(LocalDateTime.now());
        forbidden(() -> guard.requireListenerProfile(viewer));
        user.setErasedAt(null);
        user.setId(UUID.randomUUID());
        forbidden(() -> guard.requireListenerProfile(viewer));
    }

    @Test void missingProfileOrAnonymousCannotUseFeed() {
        authorize();
        when(listeners.findByUserId(viewer)).thenReturn(Optional.empty());
        forbidden(() -> guard.requireListenerProfile(viewer));
        assertThatThrownBy(() -> guard.requireListenerProfile(null)).isInstanceOfSatisfying(SoundConnectException.class,
                error -> assertThat(error.getErrorType()).isEqualTo(ErrorType.UNAUTHORIZED));
    }

    private ListenerProfile authorize() {
        when(users.findRoleNamesByUserId(viewer)).thenReturn(Set.of("ROLE_LISTENER"));
        when(users.findExistingPersonalProfileRoleNames(viewer)).thenReturn(Set.of("ROLE_LISTENER"));
        var profile = ListenerProfile.builder().id(UUID.randomUUID())
                .user(User.builder().id(viewer).status(UserStatus.ACTIVE).emailVerified(true).build()).build();
        when(listeners.findByUserId(viewer)).thenReturn(Optional.of(profile));
        return profile;
    }

    private void forbidden(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(SoundConnectException.class,
                error -> assertThat(error.getErrorType()).isEqualTo(ErrorType.FORBIDDEN_ACCESS));
    }
}
