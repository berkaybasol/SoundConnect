package com.berkayb.soundconnect.shared.config;

import com.berkayb.soundconnect.shared.mail.MailProducerImpl;
import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMqConfig {
	@Bean
	public Queue verificationMailQueue(){
		return new Queue(MailProducerImpl.VERIFICATION_MAIL_QUEUE, true);
	}
}