package com.berkayb.soundconnect.shared.realtime;

import com.berkayb.soundconnect.auth.security.JwtTokenProvider;
import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.auth.service.CustomUserDetailsService;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Authenticates STOMP CONNECT and enforces authorization on every SUBSCRIBE.
 */
@Component
@RequiredArgsConstructor
public class WebSocketSecurityInterceptor implements ChannelInterceptor {

	private final JwtTokenProvider jwtTokenProvider;
	private final CustomUserDetailsService userDetailsService;
	private final WebSocketSubscriptionAuthorizer subscriptionAuthorizer;
	private final WebSocketSessionRegistry sessionRegistry;

	@Override
	public Message<?> preSend(Message<?> message, MessageChannel channel) {
		StompHeaderAccessor accessor =
				MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
		if (accessor == null) {
			return authorizeBrokerDelivery(message) ? message : null;
		}
		if (accessor.getCommand() == null) {
			return message;
		}

		if (accessor.getCommand() == StompCommand.CONNECT) {
			authenticateConnect(accessor);
		} else if (accessor.getCommand() == StompCommand.DISCONNECT) {
			sessionRegistry.remove(accessor.getSessionId());
		} else if (accessor.getCommand() == StompCommand.SUBSCRIBE) {
			UserDetailsImpl principal = requireFreshPrincipal(accessor);
			subscriptionAuthorizer.authorize(principal, accessor.getDestination());
		} else if (accessor.getCommand() == StompCommand.SEND) {
			UserDetailsImpl principal = requireFreshPrincipal(accessor);
			subscriptionAuthorizer.authorizeSend(principal, accessor.getDestination());
		}

		return message;
	}

	private boolean authorizeBrokerDelivery(Message<?> message) {
		if (SimpMessageHeaderAccessor.getMessageType(message.getHeaders()) != SimpMessageType.MESSAGE) {
			return true;
		}
		String sessionId = SimpMessageHeaderAccessor.getSessionId(message.getHeaders());
		String destination = SimpMessageHeaderAccessor.getDestination(message.getHeaders());
		if (sessionId == null || sessionId.isBlank() || destination == null) {
			return false;
		}
		try {
			UserDetailsImpl principal = requireFreshPrincipal(sessionId);
			subscriptionAuthorizer.authorize(principal, destination);
			return true;
		} catch (AuthenticationException exception) {
			// Broker deliveries can race with logout, token expiry or a
			// development database reset. The durable notification is already
			// persisted; silently drop only this stale socket delivery.
			sessionRegistry.remove(sessionId);
			return false;
		}
	}

	private void authenticateConnect(StompHeaderAccessor accessor) {
		String token = resolveBearerToken(accessor);
		if (token == null || !jwtTokenProvider.validateToken(token)) {
			throw new BadCredentialsException("Missing or invalid WebSocket token");
		}

		final UserDetails userDetails;
		final UUID userId;
		final long expiresAt;
		try {
			userId = jwtTokenProvider.getUserIdFromToken(token);
			userDetails = userDetailsService.loadUserById(userId);
			expiresAt = jwtTokenProvider.getExpirationFromToken(token).getTime();
		} catch (RuntimeException exception) {
			throw new BadCredentialsException("Missing or invalid WebSocket token", exception);
		}

		if (!isAccountUsable(userDetails)) {
			throw new DisabledException("User account is not active");
		}
		if (!(userDetails instanceof UserDetailsImpl principal)
				|| principal.getUser().getRoles() == null
				|| principal.getUser().getRoles().isEmpty()) {
			throw new DisabledException("User profile onboarding is incomplete");
		}
		sessionRegistry.register(accessor.getSessionId(), userId, expiresAt);

		UsernamePasswordAuthenticationToken authentication =
				new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
		accessor.setUser(authentication);
	}

	private UserDetailsImpl requireFreshPrincipal(StompHeaderAccessor accessor) {
		UserDetailsImpl freshPrincipal = requireFreshPrincipal(accessor.getSessionId());
		accessor.setUser(new UsernamePasswordAuthenticationToken(
				freshPrincipal,
				null,
				freshPrincipal.getAuthorities()
		));
		return freshPrincipal;
	}

	private UserDetailsImpl requireFreshPrincipal(String sessionId) {
		WebSocketSessionRegistry.SessionContext session = sessionRegistry.find(sessionId)
				.orElseThrow(() -> new AuthenticationCredentialsNotFoundException(
						"Authenticated WebSocket session required"
				));
		if (session.expiresAtEpochMillis() <= System.currentTimeMillis()) {
			sessionRegistry.remove(sessionId);
			throw new BadCredentialsException("WebSocket token has expired");
		}

		final UserDetails freshUserDetails;
		try {
			freshUserDetails = userDetailsService.loadUserById(session.userId());
		} catch (RuntimeException exception) {
			throw new AuthenticationCredentialsNotFoundException(
					"Authenticated WebSocket session required",
					exception
			);
		}
		if (!(freshUserDetails instanceof UserDetailsImpl freshPrincipal)
				|| !isAccountUsable(freshPrincipal)
				|| freshPrincipal.getUser().getRoles() == null
				|| freshPrincipal.getUser().getRoles().isEmpty()) {
			throw new DisabledException("User account is not active");
		}
		return freshPrincipal;
	}

	private boolean isAccountUsable(UserDetails userDetails) {
		return userDetails.isEnabled()
				&& userDetails.isAccountNonLocked()
				&& userDetails.isAccountNonExpired()
				&& userDetails.isCredentialsNonExpired();
	}

	private String resolveBearerToken(StompHeaderAccessor accessor) {
		List<String> authorization = accessor.getNativeHeader("Authorization");
		if (authorization == null || authorization.isEmpty()) {
			authorization = accessor.getNativeHeader("authorization");
		}
		if (authorization == null || authorization.isEmpty()) {
			return null;
		}
		String value = authorization.getFirst();
		if (value == null || !value.startsWith("Bearer ")) {
			return null;
		}
		String token = value.substring(7).trim();
		return token.isEmpty() ? null : token;
	}
}
