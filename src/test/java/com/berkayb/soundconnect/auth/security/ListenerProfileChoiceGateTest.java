package com.berkayb.soundconnect.auth.security;

import com.berkayb.soundconnect.modules.profile.ListenerProfile.support.ListenerProfileChoiceStatusReader;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ListenerProfileChoiceGateTest {

	@Mock ListenerProfileChoiceStatusReader choiceStatusReader;

	@Test
	void pendingListenerIsRejectedFromAProductEndpoint() {
		User listener = userWithRole("ROLE_LISTENER");
		when(choiceStatusReader.requiresChoice(listener)).thenReturn(true);
		ListenerProfileChoiceGate gate = new ListenerProfileChoiceGate(choiceStatusReader);

		assertThat(gate.shouldReject(
				new MockHttpServletRequest("POST", "/api/v1/overthinking/create"),
				authentication(listener)
		)).isTrue();
	}

	@Test
	void pendingListenerCanReadOwnerProfileAndCompleteChoiceWithoutAStateQuery() {
		User listener = userWithRole("ROLE_LISTENER");
		Authentication authentication = authentication(listener);
		ListenerProfileChoiceGate gate = new ListenerProfileChoiceGate(choiceStatusReader);

		assertThat(gate.shouldReject(
				new MockHttpServletRequest("GET", "/api/v1/user/listener-profiles/me"),
				authentication
		)).isFalse();
		assertThat(gate.shouldReject(
				new MockHttpServletRequest("PATCH", "/api/v1/user/listener-profiles/me/avatar"),
				authentication
		)).isFalse();
		assertThat(gate.shouldReject(
				new MockHttpServletRequest("PATCH", "/api/v1/user/listener-profiles/me/visibility"),
				authentication
		)).isFalse();
		verifyNoInteractions(choiceStatusReader);
	}

	@Test
	void allowlistIsMethodAndContextPathSpecific() {
		User listener = userWithRole("ROLE_LISTENER");
		when(choiceStatusReader.requiresChoice(listener)).thenReturn(true);
		ListenerProfileChoiceGate gate = new ListenerProfileChoiceGate(choiceStatusReader);
		MockHttpServletRequest wrongMethod = new MockHttpServletRequest(
				"PUT", "/api/v1/user/listener-profiles/me/visibility");
		MockHttpServletRequest contextPathRequest = new MockHttpServletRequest(
				"PATCH", "/soundconnect/api/v1/user/listener-profiles/me/visibility");
		contextPathRequest.setContextPath("/soundconnect");

		assertThat(gate.shouldReject(wrongMethod, authentication(listener))).isTrue();
		assertThat(gate.shouldReject(contextPathRequest, authentication(listener))).isFalse();
	}

	@Test
	void unauthenticatedAndNonListenerRequestsNeverQueryChoiceState() {
		ListenerProfileChoiceGate gate = new ListenerProfileChoiceGate(choiceStatusReader);
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/overthinking/create");

		assertThat(gate.shouldReject(request, null)).isFalse();
		assertThat(gate.shouldReject(request, authentication(userWithRole("ROLE_MUSICIAN")))).isFalse();
		verifyNoInteractions(choiceStatusReader);
	}

	@Test
	void completedListenerContinuesNormally() {
		User listener = userWithRole("ROLE_LISTENER");
		when(choiceStatusReader.requiresChoice(listener)).thenReturn(false);
		ListenerProfileChoiceGate gate = new ListenerProfileChoiceGate(choiceStatusReader);

		assertThat(gate.shouldReject(
				new MockHttpServletRequest("POST", "/api/v1/overthinking/create"),
				authentication(listener)
		)).isFalse();
	}

	private Authentication authentication(User user) {
		UserDetailsImpl principal = UserDetailsImpl.fromUser(user);
		return new UsernamePasswordAuthenticationToken(
				principal,
				null,
				principal.getAuthorities()
		);
	}

	private User userWithRole(String roleName) {
		return User.builder()
				.id(UUID.randomUUID())
				.username("listener")
				.email("listener@example.com")
				.password("encoded")
				.status(UserStatus.ACTIVE)
				.emailVerified(true)
				.roles(Set.of(Role.builder().name(roleName).build()))
				.build();
	}
}
