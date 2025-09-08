package com.berkayb.soundconnect.shared.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.apache.http.HttpHeaders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

@Configuration
public class MailerSendHttpClientConfig {
	
	@Bean
	public WebClient webClient(
			@Value("${mailersend.api-key}")
			String apikey,
			@Value("${mailersend.connectTimeoutMs}")
			int connectTimeoutsMs,
			@Value("${mailersend.readTimeoutSec}")
			int readTimeoutSec
	) {
		HttpClient http = HttpClient.create()
				.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutsMs)
				.responseTimeout(Duration.ofSeconds(readTimeoutSec))
				.doOnConnected(conn -> conn
						.addHandlerLast(new ReadTimeoutHandler(readTimeoutSec))
						.addHandlerLast(new WriteTimeoutHandler(readTimeoutSec)));
		
		return WebClient.builder()
				.clientConnector(new ReactorClientHttpConnector(http))
				.baseUrl("https://api.mailersend.com/v1/email")
				.defaultHeader(HttpHeaders.AUTHORIZATION,"Bearer " + apikey)
				.defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
				.build();
	}
}