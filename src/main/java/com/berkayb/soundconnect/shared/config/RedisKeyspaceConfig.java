package com.berkayb.soundconnect.shared.config;

import com.berkayb.soundconnect.modules.collab.ttl.listener.CollabExpirationListener;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;

@Slf4j
@Configuration
@RequiredArgsConstructor
@Profile("!test")
public class RedisKeyspaceConfig {
	
	/**
	 * Redis expiration eventlerini dinlemek icin gerekli listener adapter
	 * CollabExpirationListener icinden "onMessage" metodu tetiklenir.
	 */
	@Bean
	public MessageListenerAdapter collabExpirationListenerAdapter(CollabExpirationListener listener) {
		return new MessageListenerAdapter(listener, "onMessage");
	}
	
	/**
	 * Keyspace notification dinleyicisi
	 * "__keyevent@*__:expired:" pattern'i tum DB indexlerdeki expire olaylarini dinler.
	 *
	 * Redis sunucusunda notify-keyspace-events ayarinin "Ex" olmasi gerekir.
	 */
	
	@Bean
	public RedisMessageListenerContainer keyExpirationListenerContainer(
			RedisConnectionFactory connectionFactory,
			MessageListenerAdapter collabExpirationListenerAdapter
	) {
		RedisMessageListenerContainer container = new RedisMessageListenerContainer();
		container.setConnectionFactory(connectionFactory);
		
		// tum dblerdeki expiration eventlerini dinle
		container.addMessageListener(
				collabExpirationListenerAdapter,
				new PatternTopic("__keyevent@*__:expired")
		);
		
		log.info("[RedisKeySpaceConfig] Key expiration listener active for Collab TTL.");
		return container;
	}
}