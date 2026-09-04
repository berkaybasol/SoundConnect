package com.berkayb.soundconnect.modules.spotify.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Configuration
@RequiredArgsConstructor
public class SpotifyWebClientConfig {
	
	private final SpotifyProperties props;
	
	@Bean
	public WebClient spotifyApiWebClient() {
		HttpClient httpClient = HttpClient.create()
		                                  .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, props.getHttp().getConnectTimeoutMs())
		                                  .responseTimeout(Duration.ofMillis(props.getHttp().getResponseTimeoutMs()))
		                                  .doOnConnected(conn -> conn
				                                  .addHandlerLast(new ReadTimeoutHandler(props.getHttp().getReadTimeoutMs(), TimeUnit.MILLISECONDS))
				                                  .addHandlerLast(new WriteTimeoutHandler(props.getHttp().getWriteTimeoutMs(), TimeUnit.MILLISECONDS))
		                                  );
		
		return WebClient.builder()
		                .baseUrl(props.getApiBaseUrl())
		                .clientConnector(new ReactorClientHttpConnector(httpClient))
		                .build();
	}
	
	@Bean
	public WebClient spotifyTokenWebClient() {
		HttpClient httpClient = HttpClient.create()
		                                  .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, props.getHttp().getConnectTimeoutMs())
		                                  .responseTimeout(Duration.ofMillis(props.getHttp().getResponseTimeoutMs()));
		
		return WebClient.builder()
		                .clientConnector(new ReactorClientHttpConnector(httpClient))
		                .build();
	}

	@Bean(name = "spotifyOEmbedConnectionProvider", destroyMethod = "dispose")
	public ConnectionProvider spotifyOEmbedConnectionProvider() {
		return ConnectionProvider.builder("spotify-oembed")
		                         .maxConnections(props.getHttp().getOEmbedMaxConnections())
		                         .pendingAcquireMaxCount(
			                         props.getHttp().getOEmbedPendingAcquireMaxCount())
		                         .pendingAcquireTimeout(Duration.ofMillis(
			                         props.getHttp().getOEmbedPendingAcquireTimeoutMs()))
		                         .build();
	}

	@Bean
	public WebClient spotifyOEmbedWebClient(
			@Qualifier("spotifyOEmbedConnectionProvider") ConnectionProvider connectionProvider
	) {
		HttpClient httpClient = HttpClient.create(connectionProvider)
		                                  .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, props.getHttp().getConnectTimeoutMs())
		                                  .responseTimeout(Duration.ofMillis(props.getHttp().getResponseTimeoutMs()))
		                                  .doOnConnected(conn -> conn
			                                  .addHandlerLast(new ReadTimeoutHandler(
				                                  props.getHttp().getReadTimeoutMs(), TimeUnit.MILLISECONDS))
			                                  .addHandlerLast(new WriteTimeoutHandler(
				                                  props.getHttp().getWriteTimeoutMs(), TimeUnit.MILLISECONDS))
		                                  );

		return WebClient.builder()
		                .baseUrl(props.getOEmbedBaseUrl())
		                .clientConnector(new ReactorClientHttpConnector(httpClient))
		                .codecs(configurer -> configurer.defaultCodecs()
			                .maxInMemorySize(props.getHttp().getOEmbedMaxResponseBytes()))
		                .build();
	}
}
