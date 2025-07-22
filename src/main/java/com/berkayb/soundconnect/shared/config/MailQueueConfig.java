package com.berkayb.soundconnect.shared.config;

import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ için queue (kuyruk) otomatik oluşturma config’i.
 * Kuyruk yoksa Spring Boot başlatınca otomatik yaratır!
 */
@Configuration
public class MailQueueConfig {
	
	@Bean
	public Queue emailVerificationQueue() {
		// Durable = true: Kuyruk restartlarda silinmez!
		return new Queue("email-verification-queue", true);
	}
}