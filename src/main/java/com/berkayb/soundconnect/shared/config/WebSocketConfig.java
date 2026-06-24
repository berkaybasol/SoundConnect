package com.berkayb.soundconnect.shared.config;

import com.berkayb.soundconnect.auth.security.JwtTokenProvider;
import com.berkayb.soundconnect.auth.service.CustomUserDetailsService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.util.List;
import java.util.UUID;

/**
 * WebSocket uzerinden STOMP protokolu ile mesajlasma altyapisini yapilandiran konfigurasyon sinifi.
 */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

	private final JwtTokenProvider jwtTokenProvider;
	private final CustomUserDetailsService userDetailsService;

	@Override
	public void configureMessageBroker(MessageBrokerRegistry config) {
		config.enableSimpleBroker("/topic");
		config.setApplicationDestinationPrefixes("/app");
	}

	@Override
	public void registerStompEndpoints(StompEndpointRegistry registry) {
		registry.addEndpoint("/ws")
				.setAllowedOriginPatterns("*")
				.withSockJS();
	}

	@Override
	public void configureClientInboundChannel(ChannelRegistration registration) {
		registration.interceptors(new ChannelInterceptor() {
			@Override
			public Message<?> preSend(Message<?> message, MessageChannel channel) {
				StompHeaderAccessor accessor =
						MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
				if (accessor == null || accessor.getCommand() != StompCommand.CONNECT) {
					return message;
				}
				String token = resolveBearerToken(accessor);
				if (token == null || !jwtTokenProvider.validateToken(token)) {
					throw new IllegalArgumentException("Missing or invalid WebSocket token");
				}
				UserDetails userDetails;
				try {
					UUID userId = jwtTokenProvider.getUserIdFromToken(token);
					userDetails = userDetailsService.loadUserById(userId);
				} catch (RuntimeException e) {
					throw new IllegalArgumentException("Missing or invalid WebSocket token", e);
				}
				UsernamePasswordAuthenticationToken authentication =
						new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
				accessor.setUser(authentication);
				return message;
			}
		});
	}

	private String resolveBearerToken(StompHeaderAccessor accessor) {
		List<String> authorization = accessor.getNativeHeader("Authorization");
		if (authorization == null || authorization.isEmpty()) {
			authorization = accessor.getNativeHeader("authorization");
		}
		if (authorization == null || authorization.isEmpty()) {
			return null;
		}
		String value = authorization.get(0);
		if (value == null || !value.startsWith("Bearer ")) {
			return null;
		}
		return value.substring(7);
	}
}
