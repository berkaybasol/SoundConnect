package com.berkayb.soundconnect.shared.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * WebSocket uzerinden STOMP protokolu ile masajlasma altyapisini yapilandiran konfigurasyon sinifi.
 * - Client baglanti noktasi: /ws -> ws://host/ws uzerinden baglanilir.
 * - Server tarafi mesaj alma prefix'i: /app -> Client mesaj gonderirken kullanir(orn: send("/app/..."))
 * - Server tarafi mesaj yayinlama prefix'i: /topic -> Client bu adreslere subscribe olur
 *
 * Flutter/Web client
 * - baglan: ws(s)://<host>/ws
 * - dinle(subscribe): /topic/notifications/{userId}
 * - yayin (server publish): simpMessagingTemplate.convertAndSend("/topic/notifications/" + uderId, payload)
 */
@Configuration
@EnableWebSocketMessageBroker // WebSocket mesajlasmasini STOMP protokolu ile aktif eder
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
	
	@Override
	public void configureMessageBroker(MessageBrokerRegistry config) {
		// Serverin mesaj yayinladigi (publish ettigi) adres prefix'i -> Client bu adreslere subscribe olur
		config.enableSimpleBroker("/topic");
		
		
		// Uygulamanin controller/service tarafinda @MessageMapping ile dinledigi prefix
		config.setApplicationDestinationPrefixes("/app");
	}
	
	@Override
	public void registerStompEndpoints(StompEndpointRegistry registry) {
		// STOMP endpoint: ws(s)://host/ws
		registry.addEndpoint("/ws")
				.setAllowedOriginPatterns("*") //FIXME prod'da kisitlicaz
		 .withSockJS(); // Gerekirse aç (eski tarayıcılar için)
	}
}