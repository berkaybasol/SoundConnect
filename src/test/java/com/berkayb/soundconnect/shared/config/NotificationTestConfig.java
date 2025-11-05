package com.berkayb.soundconnect.shared.config;

import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationProducer;
import org.mockito.Mockito;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class NotificationTestConfig {
	
	@Bean
	@Primary
	public NotificationProducer notificationProducer() {
		// Bütün testler için geçerli olacak global mock
		return Mockito.mock(NotificationProducer.class);
	}
}