package com.berkayb.soundconnect.shared.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * Spring Boot'da dis servislerle orn: ucuncu parti API, mail provider, vs.)
 * HTTP uzerinden iletisim kurmak icin kullandigimiz Config Class
 */
@Configuration
public class RestTemplateConfig {
	@Bean
	public RestTemplate restTemplate() {
		return new RestTemplate();
	}
}