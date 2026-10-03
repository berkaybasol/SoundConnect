package com.berkayb.soundconnect.modules.message.dm.service;

import com.berkayb.soundconnect.auth.security.ListenerProfileChoiceGate;
import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.support.ListenerProfileChoiceStatusReader;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.role.enums.RoleEnum;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.util.Set;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** The real profile-choice boundary must not accidentally reserve DMs for professional profiles. */
class DmProfileAccessTest {
    private final ListenerProfileChoiceStatusReader status = mock(ListenerProfileChoiceStatusReader.class);
    private final ListenerProfileChoiceGate gate = new ListenerProfileChoiceGate(status);

    @ParameterizedTest @EnumSource(RoleEnum.class)
    void allActualRolesWithCompletedOnboardingCanUseEveryDmRoute(RoleEnum role) {
        var user = user(role);
        var auth = authentication(user);
        for (var request : requests()) assertThat(gate.shouldReject(request, auth)).isFalse();
    }

    @Test void pendingListenerChoiceCannotReadSendAcknowledgeOrCountDmEvenWithValidPrincipal() {
        var user = user(RoleEnum.ROLE_LISTENER);
        when(status.requiresChoice(user)).thenReturn(true);
        for (var request : requests()) assertThat(gate.shouldReject(request, authentication(user))).isTrue();
    }

    @Test void listenerCompletionImmediatelyUnlocksDmWithoutReissuingThePrincipal() {
        var user = user(RoleEnum.ROLE_LISTENER);
        when(status.requiresChoice(user)).thenReturn(true, false);
        var auth = authentication(user);
        var request = new MockHttpServletRequest("POST", "/api/v1/user/dm/messages");
        assertThat(gate.shouldReject(request, auth)).isTrue();
        assertThat(gate.shouldReject(request, auth)).isFalse();
    }

    private User user(RoleEnum role) {
        return User.builder().id(UUID.randomUUID()).username("isolated_dm_fixture").status(UserStatus.ACTIVE)
                .emailVerified(true).roles(Set.of(Role.builder().name(role.name()).build())).build();
    }
    private UsernamePasswordAuthenticationToken authentication(User user) {
        var principal = new UserDetailsImpl(user);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }
    private java.util.List<MockHttpServletRequest> requests() {
        return java.util.List.of(new MockHttpServletRequest("GET", "/api/v1/user/dm/conversations/my"),
                new MockHttpServletRequest("POST", "/api/v1/user/dm/conversations/between"),
                new MockHttpServletRequest("GET", "/api/v1/user/dm/messages/conversation/" + UUID.randomUUID()),
                new MockHttpServletRequest("POST", "/api/v1/user/dm/messages"),
                new MockHttpServletRequest("PATCH", "/api/v1/user/dm/messages/" + UUID.randomUUID() + "/read"),
                new MockHttpServletRequest("GET", "/api/v1/user/dm/unread-count"));
    }
}
