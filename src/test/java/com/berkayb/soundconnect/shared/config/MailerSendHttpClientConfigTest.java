package com.berkayb.soundconnect.shared.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.*;

class MailerSendHttpClientConfigTest {
	
	@Test
	@DisplayName("WebClient: baseUrl ve Authorization header doğru set edilmeli (exchangeFunction yakalama)")
	void webclient_ok() {
		MailerSendHttpClientConfig cfg = new MailerSendHttpClientConfig();
		
		String apiKey = "test-api-key-123";
		int connectMs = 1234;
		int readSec = 5;
		
		WebClient client = cfg.webClient(apiKey, connectMs, readSec);
		assertThat(client).isNotNull();
		
		// ExchangeFunction stub: gelen request’i yakalayalım
		java.util.concurrent.atomic.AtomicReference<org.springframework.web.reactive.function.client.ClientRequest> captured =
				new java.util.concurrent.atomic.AtomicReference<>();
		
		WebClient probe = client.mutate()
		                        .exchangeFunction(req -> {
			                        captured.set(req);
			                        return reactor.core.publisher.Mono.just(
					                        org.springframework.web.reactive.function.client.ClientResponse.create(org.springframework.http.HttpStatus.OK).build()
			                        );
		                        })
		                        .build();
		
		// Bir POST çağrısı yap (body verelim ki Content-Type kesin yerleşsin)
		probe.post()
		     .uri("/probe")
		     .bodyValue("{}")
		     .retrieve()
		     .toBodilessEntity()
		     .block();
		
		var req = captured.get();
		assertThat(req).isNotNull();
		
		// 1) baseUrl + path birleşimi doğru mu?
		assertThat(req.url().toString()).isEqualTo("https://api.mailersend.com/v1/email/probe");
		
		// 2) Authorization header
		assertThat(req.headers().getFirst(org.apache.http.HttpHeaders.AUTHORIZATION))
				.isEqualTo("Bearer " + apiKey);
		
		// 3) Content-Type (default header'dan application/json bekliyoruz)
		assertThat(req.headers().getFirst(org.apache.http.HttpHeaders.CONTENT_TYPE))
				.contains("application/json");
	}
}