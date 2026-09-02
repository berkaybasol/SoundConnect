package com.berkayb.soundconnect.shared.config;

import com.berkayb.soundconnect.shared.realtime.WebSocketSecurityInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
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

	@Value("${app.websocket.broker-relay.enabled:false}")
	private boolean brokerRelayEnabled;
	@Value("${app.websocket.broker-relay.host:${SPRING_RABBITMQ_HOST:localhost}}")
	private String brokerRelayHost;
	@Value("${app.websocket.broker-relay.port:61613}")
	private int brokerRelayPort;
	@Value("${app.websocket.broker-relay.login:${SPRING_RABBITMQ_USERNAME:guest}}")
	private String brokerRelayLogin;
	@Value("${app.websocket.broker-relay.passcode:${SPRING_RABBITMQ_PASSWORD:guest}}")
	private String brokerRelayPasscode;
	@Value("${app.websocket.broker-relay.virtual-host:${SPRING_RABBITMQ_VIRTUAL_HOST:/}}")
	private String brokerRelayVirtualHost;

	@Override
	public void configureMessageBroker(MessageBrokerRegistry config) {
		if (brokerRelayEnabled) {
			config.enableStompBrokerRelay("/topic")
					.setRelayHost(brokerRelayHost)
					.setRelayPort(brokerRelayPort)
					.setClientLogin(brokerRelayLogin)
					.setClientPasscode(brokerRelayPasscode)
					.setSystemLogin(brokerRelayLogin)
					.setSystemPasscode(brokerRelayPasscode)
					.setVirtualHost(brokerRelayVirtualHost)
					.setSystemHeartbeatSendInterval(10_000)
					.setSystemHeartbeatReceiveInterval(10_000);
		} else {
			config.enableSimpleBroker("/topic");
		}
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
