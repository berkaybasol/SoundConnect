package com.berkayb.soundconnect.modules.feed.musician.core;

import com.berkayb.soundconnect.modules.profile.StudioProfile.entity.StudioProfile;
import com.berkayb.soundconnect.modules.profile.StudioProfile.repository.StudioProfileRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.repository.MusicianProfileRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StudioFeedViewerGuardTest {
    private final UserRepository users = mock(UserRepository.class);
    private final MusicianProfileRepository musicians = mock(MusicianProfileRepository.class);
    private final StudioProfileRepository studios = mock(StudioProfileRepository.class);
    private final MusicianFeedViewerGuard guard = new MusicianFeedViewerGuard(users, musicians, null, null, null, studios);
    private final UUID viewer = UUID.randomUUID();

    @Test void verifiedActiveStudioOwnsItsProfileBoundFeedWithoutMusicianFallback() {
        var profile = authorize();
        assertThat(guard.requireProfile(viewer, BackstageFeedAudience.STUDIO)).isEqualTo(profile.getId());
        assertThat(BackstageFeedAudience.STUDIO.algorithmVersion()).isEqualTo("studio-v1.0.0");
        when(users.findRoleNamesByUserId(viewer)).thenReturn(Set.of("ROLE_STUDIO", "ROLE_ADMIN"));
        assertThat(guard.requireStudioProfile(viewer)).isEqualTo(profile.getId());
        verifyNoInteractions(musicians);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_MUSICIAN", "ROLE_LISTENER", "ROLE_VENUE", "ROLE_ORGANIZER", "ROLE_PRODUCER"})
    void anotherPersonalRoleOrProfileVetoesStudioEvenWhenAdmin(String conflictingRole) {
        authorize();
        when(users.findRoleNamesByUserId(viewer)).thenReturn(Set.of("ROLE_STUDIO", conflictingRole, "ROLE_ADMIN"));
        forbidden(() -> guard.requireStudioProfile(viewer));
        when(users.findRoleNamesByUserId(viewer)).thenReturn(Set.of("ROLE_STUDIO", "ROLE_ADMIN"));
        when(users.findExistingPersonalProfileRoleNames(viewer)).thenReturn(Set.of("ROLE_STUDIO", conflictingRole));
        forbidden(() -> guard.requireStudioProfile(viewer));
        verifyNoInteractions(studios);
    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, mode = EnumSource.Mode.EXCLUDE, names = "ACTIVE")
    void pendingRejectedAndInactiveMembershipsCannotReadOrMutateFeed(UserStatus status) {
        var profile = authorize();
        profile.getUser().setStatus(status);
        forbidden(() -> guard.requireStudioProfile(viewer));
    }

    @Test void staleUnverifiedErasedOrWrongOwnerCannotUseFeed() {
        var profile = authorize();
        var user = profile.getUser();
        user.setEmailVerified(false);
        forbidden(() -> guard.requireStudioProfile(viewer));
        user.setEmailVerified(null);
        forbidden(() -> guard.requireStudioProfile(viewer));
        user.setEmailVerified(true);
        user.setErasedAt(LocalDateTime.now());
        forbidden(() -> guard.requireStudioProfile(viewer));
        user.setErasedAt(null);
        user.setId(UUID.randomUUID());
        forbidden(() -> guard.requireStudioProfile(viewer));
        profile.setUser(null);
        forbidden(() -> guard.requireStudioProfile(viewer));
    }

    @Test void missingRoleProfileIdentityOrAnonymousNeverFallsBackToAnotherFeed() {
        var profile = authorize();
        profile.setId(null);
        forbidden(() -> guard.requireStudioProfile(viewer));
        when(studios.findByUserId(viewer)).thenReturn(Optional.empty());
        forbidden(() -> guard.requireStudioProfile(viewer));
        when(users.findExistingPersonalProfileRoleNames(viewer)).thenReturn(Set.of());
        forbidden(() -> guard.requireStudioProfile(viewer));
        when(users.findExistingPersonalProfileRoleNames(viewer)).thenReturn(Set.of("ROLE_STUDIO"));
        when(users.findRoleNamesByUserId(viewer)).thenReturn(Set.of("ROLE_MUSICIAN"));
        forbidden(() -> guard.requireStudioProfile(viewer));
        assertThatThrownBy(() -> guard.requireStudioProfile(null)).isInstanceOfSatisfying(SoundConnectException.class,
                error -> assertThat(error.getErrorType()).isEqualTo(ErrorType.UNAUTHORIZED));
        verifyNoInteractions(musicians);
    }

    private StudioProfile authorize() {
        when(users.findRoleNamesByUserId(viewer)).thenReturn(Set.of("ROLE_STUDIO"));
        when(users.findExistingPersonalProfileRoleNames(viewer)).thenReturn(Set.of("ROLE_STUDIO"));
        var profile = StudioProfile.builder().id(UUID.randomUUID())
                .user(User.builder().id(viewer).status(UserStatus.ACTIVE).emailVerified(true).build()).build();
        when(studios.findByUserId(viewer)).thenReturn(Optional.of(profile));
        return profile;
    }

    private void forbidden(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(SoundConnectException.class,
                error -> assertThat(error.getErrorType()).isEqualTo(ErrorType.FORBIDDEN_ACCESS));
    }
}
