package com.berkayb.soundconnect.shared.config;

import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

// RabbitMQ'da mesajlari otomatik olarak JSON'a serialize/deserialize etmek icin gerekli converter sinifidir.
@Configuration
@EnableRabbit // Spring Boot'un RabbitMQ anatasyonlarini aktif hale getirmek icin kullanilan anatasyon
@Profile("!test")
public class RabbitMqConfig {
	@Bean
	public Jackson2JsonMessageConverter jackson2JsonMessageConverter() {
		return new Jackson2JsonMessageConverter(
				"com.berkayb.soundconnect",
				"com.berkayb.soundconnect.modules.media.dto.request",
				"java.util",
				"java.lang"
		);
	}
}