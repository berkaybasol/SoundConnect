package com.berkayb.soundconnect.shared.config;

import com.berkayb.soundconnect.shared.realtime.WebSocketSecurityInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP/WebSocket transport configuration. Authentication and destination
 * authorization are delegated to a testable named interceptor.
 */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

	private final WebSocketSecurityInterceptor webSocketSecurityInterceptor;
	private final CorsProperties corsProperties;

	@Override
	public void configureMessageBroker(MessageBrokerRegistry config) {
		config.enableSimpleBroker("/topic");
		config.setApplicationDestinationPrefixes("/app");
	}

	@Override
	public void registerStompEndpoints(StompEndpointRegistry registry) {
		String[] allowedOrigins = corsProperties.requireSafeAllowedOriginPatterns().toArray(String[]::new);
		registry.addEndpoint("/ws")
				.setAllowedOriginPatterns(allowedOrigins)
				.withSockJS();
	}

	@Override
	public void configureClientInboundChannel(ChannelRegistration registration) {
		registration.interceptors(webSocketSecurityInterceptor);
	}

	@Override
	public void configureClientOutboundChannel(ChannelRegistration registration) {
		registration.interceptors(webSocketSecurityInterceptor);
	}
}
