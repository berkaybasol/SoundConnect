package com.berkayb.soundconnect.modules.collab.support;

import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CollabAccessGuardTest {
    private final UserRepository users = mock(UserRepository.class);
    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final CollabAccessGuard guard = new CollabAccessGuard(users, jdbc);
    private final UUID viewer = UUID.randomUUID();

    @ParameterizedTest @ValueSource(strings = {"ROLE_MUSICIAN", "ROLE_VENUE", "ROLE_STUDIO"})
    void canonicalActiveBusinessProfileIsAcceptedAndFreshChangesAreRejected(String role) {
        when(users.findRoleNamesByUserId(viewer)).thenReturn(Set.of(role, "ROLE_USER"));
        when(users.findExistingPersonalProfileRoleNames(viewer)).thenReturn(Set.of(role));
        when(jdbc.queryForObject(anyString(), eq(Map.of("id", viewer)), eq(Boolean.class))).thenReturn(true);
        assertThatCode(() -> guard.requireBackstage(viewer)).doesNotThrowAnyException();
        when(users.findRoleNamesByUserId(viewer)).thenReturn(Set.of(role, "ROLE_LISTENER"));
        forbidden(() -> guard.requireBackstage(viewer));
        when(users.findRoleNamesByUserId(viewer)).thenReturn(Set.of(role));
        when(users.findExistingPersonalProfileRoleNames(viewer)).thenReturn(Set.of("ROLE_LISTENER"));
        forbidden(() -> guard.requireBackstage(viewer));
        when(users.findExistingPersonalProfileRoleNames(viewer)).thenReturn(Set.of(role));
        when(jdbc.queryForObject(anyString(), anyMap(), eq(Boolean.class))).thenReturn(false);
        forbidden(() -> guard.requireBackstage(viewer));
    }

    @Test void missingProfileAndMultiplePersonalProfilesCannotBorrowABusinessRole() {
        when(users.findRoleNamesByUserId(viewer)).thenReturn(Set.of("ROLE_MUSICIAN"));
        when(users.findExistingPersonalProfileRoleNames(viewer)).thenReturn(Set.of());
        forbidden(() -> guard.requireBackstage(viewer));
        when(users.findExistingPersonalProfileRoleNames(viewer)).thenReturn(Set.of("ROLE_MUSICIAN", "ROLE_VENUE"));
        forbidden(() -> guard.requireBackstage(viewer));
        verifyNoInteractions(jdbc);
    }

    @Test void listenerRoleOrRetainedListenerProfileCannotUseModerationPermission() {
        when(users.findRoleNamesByUserId(viewer)).thenReturn(Set.of("ROLE_ADMIN", "ROLE_LISTENER"));
        forbidden(() -> guard.requireModerator(viewer));
        when(users.findRoleNamesByUserId(viewer)).thenReturn(Set.of("ROLE_ADMIN"));
        when(users.findExistingPersonalProfileRoleNames(viewer)).thenReturn(Set.of("ROLE_LISTENER"));
        forbidden(() -> guard.requireModerator(viewer));
        verifyNoInteractions(jdbc);
    }

    @Test void staffWithoutPersonalProfileKeepsExplicitPermissionAndRevocationIsFresh() {
        when(users.findRoleNamesByUserId(viewer)).thenReturn(Set.of("ROLE_ADMIN"));
        when(users.findExistingPersonalProfileRoleNames(viewer)).thenReturn(Set.of());
        when(jdbc.queryForObject(anyString(), eq(Map.of("id", viewer)), eq(Boolean.class))).thenReturn(true, false);
        assertThatCode(() -> guard.requireModerator(viewer)).doesNotThrowAnyException();
        forbidden(() -> guard.requireModerator(viewer));
        verify(jdbc, times(2)).queryForObject(contains("MANAGE_COLLAB_REPORTS"), eq(Map.of("id", viewer)), eq(Boolean.class));
    }

    @Test void anonymousServiceCallerIsRejectedBeforeAnyRead() {
        for (Runnable attempt : new Runnable[]{() -> guard.requireBackstage(null), () -> guard.requireModerator(null)}) {
            assertThat(catchThrowableOfType(attempt::run, SoundConnectException.class).getErrorType()).isEqualTo(ErrorType.UNAUTHORIZED);
        }
        verifyNoInteractions(users, jdbc);
    }
    private void forbidden(Runnable action) {
        assertThat(catchThrowableOfType(action::run, SoundConnectException.class).getErrorType()).isEqualTo(ErrorType.COLLAB_FORBIDDEN);
    }
}
