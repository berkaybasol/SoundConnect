package com.berkayb.soundconnect.shared.realtime;

import com.berkayb.soundconnect.auth.security.JwtTokenProvider;
import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.auth.service.CustomUserDetailsService;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;

import java.util.UUID;
import java.util.Set;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebSocketSecurityInterceptorTest {

	@Mock JwtTokenProvider jwtTokenProvider;
	@Mock CustomUserDetailsService userDetailsService;
	@Mock WebSocketSubscriptionAuthorizer subscriptionAuthorizer;
	@Mock MessageChannel channel;
	@Spy WebSocketSessionRegistry sessionRegistry = new WebSocketSessionRegistry();
	@InjectMocks WebSocketSecurityInterceptor interceptor;

	@Test
	void connectAuthenticatesActiveAccountFromBearerToken() {
		UUID userId = UUID.randomUUID();
		UserDetailsImpl principal = principal(userId);
		when(jwtTokenProvider.validateToken("valid-token")).thenReturn(true);
		when(jwtTokenProvider.getUserIdFromToken("valid-token")).thenReturn(userId);
		when(jwtTokenProvider.getExpirationFromToken("valid-token"))
				.thenReturn(new Date(System.currentTimeMillis() + 60_000));
		when(userDetailsService.loadUserById(userId)).thenReturn(principal);
		StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
		accessor.setNativeHeader("Authorization", "Bearer valid-token");
		accessor.setSessionId("session-1");

		Message<?> result = interceptor.preSend(message(accessor), channel);

		StompHeaderAccessor resultAccessor = StompHeaderAccessor.wrap(result);
		assertThat(resultAccessor.getUser()).isInstanceOf(UsernamePasswordAuthenticationToken.class);
		assertThat(resultAccessor.getUser().getName()).isEqualTo(principal.getUsername());
		assertThat(sessionRegistry.find("session-1")).isPresent();
	}

	@Test
	void connectRejectsMissingToken() {
		StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);

		assertThatThrownBy(() -> interceptor.preSend(message(accessor), channel))
				.isInstanceOf(BadCredentialsException.class);
	}

	@Test
	void connectRejectsRolelessOnboardingAccount() {
		UUID userId = UUID.randomUUID();
		UserDetailsImpl roleless = principal(userId);
		roleless.getUser().setRoles(Set.of());
		when(jwtTokenProvider.validateToken("valid-token")).thenReturn(true);
		when(jwtTokenProvider.getUserIdFromToken("valid-token")).thenReturn(userId);
		when(jwtTokenProvider.getExpirationFromToken("valid-token"))
				.thenReturn(new Date(System.currentTimeMillis() + 60_000));
		when(userDetailsService.loadUserById(userId)).thenReturn(roleless);
		StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
		accessor.setNativeHeader("Authorization", "Bearer valid-token");
		accessor.setSessionId("session-roleless");

		assertThatThrownBy(() -> interceptor.preSend(message(accessor), channel))
				.isInstanceOf(DisabledException.class);
	}

	@Test
	void subscribeDelegatesDestinationAuthorizationForAuthenticatedPrincipal() {
		UUID userId = UUID.randomUUID();
		UserDetailsImpl principal = principal(userId);
		String destination = "/topic/dm/" + userId;
		StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
		accessor.setSessionId("session-subscribe");
		accessor.setDestination(destination);
		accessor.setUser(new UsernamePasswordAuthenticationToken(
				principal,
				null,
				principal.getAuthorities()
		));
		sessionRegistry.register(
				"session-subscribe",
				userId,
				System.currentTimeMillis() + 60_000
		);
		when(userDetailsService.loadUserById(userId)).thenReturn(principal);

		interceptor.preSend(message(accessor), channel);

		verify(subscriptionAuthorizer).authorize(principal, destination);
	}

	@Test
	void sendToBrokerTopicIsDeniedByDefault() {
		UUID userId = UUID.randomUUID();
		UserDetailsImpl principal = principal(userId);
		StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
		accessor.setSessionId("session-send");
		accessor.setDestination("/topic/dm/" + UUID.randomUUID());
		accessor.setUser(new UsernamePasswordAuthenticationToken(
				principal,
				null,
				principal.getAuthorities()
		));
		sessionRegistry.register("session-send", userId, System.currentTimeMillis() + 60_000);
		when(userDetailsService.loadUserById(userId)).thenReturn(principal);

		interceptor.preSend(message(accessor), channel);

		verify(subscriptionAuthorizer).authorizeSend(principal, accessor.getDestination());
	}

	@Test
	void expiredSessionIsRejectedBeforeSubscriptionAuthorization() {
		UUID userId = UUID.randomUUID();
		UserDetailsImpl principal = principal(userId);
		StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
		accessor.setSessionId("session-expired");
		accessor.setDestination("/topic/dm/" + userId);
		accessor.setUser(new UsernamePasswordAuthenticationToken(
				principal,
				null,
				principal.getAuthorities()
		));
		sessionRegistry.register("session-expired", userId, System.currentTimeMillis() - 1);

		assertThatThrownBy(() -> interceptor.preSend(message(accessor), channel))
				.isInstanceOf(BadCredentialsException.class);
	}

	@Test
	void brokerDeliveryUsesSessionRegistryWithoutStompPrincipalHeaders() {
		UUID userId = UUID.randomUUID();
		UserDetailsImpl principal = principal(userId);
		String destination = "/topic/dm/" + userId;
		sessionRegistry.register("session-outbound", userId, System.currentTimeMillis() + 60_000);
		when(userDetailsService.loadUserById(userId)).thenReturn(principal);
		SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
		accessor.setSessionId("session-outbound");
		accessor.setDestination(destination);
		accessor.setLeaveMutable(true);
		Message<byte[]> outbound = MessageBuilder.createMessage(
				new byte[0],
				accessor.getMessageHeaders()
		);

		interceptor.preSend(outbound, channel);

		verify(subscriptionAuthorizer).authorize(principal, destination);
	}

	@Test
	void brokerDeliveryDropsAndRemovesAStaleSessionWithoutFailingTheConsumer() {
		UUID userId = UUID.randomUUID();
		String sessionId = "session-stale-outbound";
		sessionRegistry.register(sessionId, userId, System.currentTimeMillis() + 60_000);
		when(userDetailsService.loadUserById(userId))
				.thenThrow(new AuthenticationCredentialsNotFoundException("user deleted"));
		SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
		accessor.setSessionId(sessionId);
		accessor.setDestination("/topic/notifications/" + userId);
		accessor.setLeaveMutable(true);
		Message<byte[]> outbound = MessageBuilder.createMessage(
				new byte[0],
				accessor.getMessageHeaders()
		);

		Message<?> result = interceptor.preSend(outbound, channel);

		assertThat(result).isNull();
		assertThat(sessionRegistry.find(sessionId)).isEmpty();
	}

	private Message<byte[]> message(StompHeaderAccessor accessor) {
		accessor.setLeaveMutable(true);
		return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
	}

	private UserDetailsImpl principal(UUID userId) {
		return new UserDetailsImpl(User.builder()
				.id(userId)
				.username("user")
				.email("user@example.com")
				.password("encoded")
				.status(UserStatus.ACTIVE)
				.emailVerified(true)
				.roles(Set.of(Role.builder().name("ROLE_MUSICIAN").build()))
				.build());
	}
}
