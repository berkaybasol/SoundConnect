package com.berkayb.soundconnect.modules.spotify.client;

import com.berkayb.soundconnect.modules.spotify.config.SpotifyProperties;
import com.berkayb.soundconnect.modules.spotify.service.SpotifyTokenService;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SpotifyApiClientImplTest {

	private MockWebServer server;
	private SpotifyApiClientImpl client;

	@BeforeEach
	void setUp() throws Exception {
		server = new MockWebServer();
		server.start();
		SpotifyProperties properties = new SpotifyProperties();
		properties.setMarket("TR");
		SpotifyTokenService tokenService = mock(SpotifyTokenService.class);
		when(tokenService.getAccessToken()).thenReturn("test-token");
		client = new SpotifyApiClientImpl(
				properties,
				tokenService,
				WebClient.builder().baseUrl(server.url("/").toString()).build()
		);
	}

	@AfterEach
	void tearDown() throws Exception {
		server.shutdown();
	}

	@Test
	void getTracksByIdsUsesSupportedSingleTrackEndpointAndPreservesOrder() throws Exception {
		server.enqueue(trackResponse("track-b", "Track B"));
		server.enqueue(trackResponse("track-a", "Track A"));

		var result = client.getTracksByIds(List.of(" track-b ", "track-a", "track-b"));

		assertThat(result).extracting("spotifyTrackId").containsExactly("track-b", "track-a");
		RecordedRequest first = server.takeRequest();
		RecordedRequest second = server.takeRequest();
		assertThat(first.getPath()).isEqualTo("/tracks/track-b?market=TR");
		assertThat(second.getPath()).isEqualTo("/tracks/track-a?market=TR");
		assertThat(first.getHeader("Authorization")).isEqualTo("Bearer test-token");
		assertThat(second.getHeader("Authorization")).isEqualTo("Bearer test-token");
	}

	@Test
	void getTracksByIdsReturnsEmptyWithoutCallingSpotifyForBlankInput() {
		assertThat(client.getTracksByIds(List.of(" "))).isEmpty();
		assertThat(server.getRequestCount()).isZero();
	}

	@Test
	void getTracksByIdsKeepsSpotifyErrorMappingForSingleTrackRequests() {
		server.enqueue(new MockResponse()
				.setResponseCode(403)
				.addHeader("Content-Type", "application/json")
				.setBody("{\"error\":{\"status\":403,\"message\":\"Forbidden\"}}"));

		assertThatThrownBy(() -> client.getTracksByIds(List.of("track-a")))
				.isInstanceOfSatisfying(SoundConnectException.class,
						exception -> assertThat(exception.getErrorType())
								.isEqualTo(ErrorType.SPOTIFY_FORBIDDEN));
	}

	private MockResponse trackResponse(String id, String name) {
		return new MockResponse()
				.setResponseCode(200)
				.addHeader("Content-Type", "application/json")
				.setBody("""
						{
						  "id":"%s",
						  "name":"%s",
						  "duration_ms":180000,
						  "explicit":false,
						  "preview_url":null,
						  "external_urls":{"spotify":"https://open.spotify.com/track/%s"},
						  "album":{"name":"Album","images":[]},
						  "artists":[{"id":"artist-1","name":"Artist"}]
						}
						""".formatted(id, name, id));
	}
}
