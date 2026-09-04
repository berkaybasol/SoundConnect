package com.berkayb.soundconnect.modules.spotify.client;

import com.berkayb.soundconnect.modules.spotify.config.SpotifyProperties;
import com.berkayb.soundconnect.modules.spotify.config.SpotifyWebClientConfig;
import com.berkayb.soundconnect.modules.spotify.dto.response.SpotifyPlaylistMetadataDto;
import com.berkayb.soundconnect.modules.spotify.service.SpotifyTokenService;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.RateLimitedException;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.Dispatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.netty.resources.ConnectionProvider;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SpotifyApiClientImplTest {

	private MockWebServer server;
	private SpotifyApiClientImpl client;
	private ConnectionProvider oEmbedConnectionProvider;

	@BeforeEach
	void setUp() throws Exception {
		server = new MockWebServer();
		server.start();
		SpotifyProperties properties = new SpotifyProperties();
		properties.setMarket("TR");
		properties.setApiBaseUrl(server.url("/").toString());
		properties.setOEmbedBaseUrl(server.url("/").toString());
		SpotifyWebClientConfig webClientConfig = new SpotifyWebClientConfig(properties);
		oEmbedConnectionProvider = webClientConfig.spotifyOEmbedConnectionProvider();
		SpotifyTokenService tokenService = mock(SpotifyTokenService.class);
		when(tokenService.getAccessToken()).thenReturn("test-token");
		client = new SpotifyApiClientImpl(
				properties,
				tokenService,
				webClientConfig.spotifyApiWebClient(),
				webClientConfig.spotifyOEmbedWebClient(oEmbedConnectionProvider)
		);
	}

	@AfterEach
	void tearDown() throws Exception {
		oEmbedConnectionProvider.dispose();
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

	@Test
	void playlistMetadataUsesOfficialOEmbedWithoutAnAccessToken() throws Exception {
		String playlistId = "37i9dQZF1DXcBWIGoYBM5M";
		server.enqueue(new MockResponse()
				.setResponseCode(200)
				.addHeader("Content-Type", "application/json")
				.setBody("""
						{
						  "title":"Today's Top Hits",
						  "thumbnail_url":"https://i.scdn.co/image/ab67706f00000002test",
						  "thumbnail_width":300,
						  "thumbnail_height":300
						}
						"""));

		var result = client.getPlaylistMetadata(playlistId);

		assertThat(result.spotifyPlaylistId()).isEqualTo(playlistId);
		assertThat(result.title()).isEqualTo("Today's Top Hits");
		assertThat(result.coverImageUrl()).isEqualTo("https://i.scdn.co/image/ab67706f00000002test");
		assertThat(result.spotifyUrl())
				.isEqualTo("https://open.spotify.com/playlist/" + playlistId);
		RecordedRequest request = server.takeRequest();
		assertThat(request.getRequestUrl().encodedPath()).isEqualTo("/oembed");
		assertThat(request.getRequestUrl().queryParameter("url"))
				.isEqualTo("https://open.spotify.com/playlist/" + playlistId);
		assertThat(request.getHeader("Authorization")).isNull();
	}

	@Test
	void playlistMetadataRejectsMissingOrUntrustedCoverArtwork() {
		server.enqueue(new MockResponse()
				.setResponseCode(200)
				.addHeader("Content-Type", "application/json")
				.setBody("""
						{"title":"Playlist","thumbnail_url":"https://attacker.test/cover.jpg"}
						"""));

		assertThatThrownBy(() -> client.getPlaylistMetadata("37i9dQZF1DXcBWIGoYBM5M"))
				.isInstanceOfSatisfying(SoundConnectException.class,
						exception -> assertThat(exception.getErrorType())
								.isEqualTo(ErrorType.SPOTIFY_PLAYLIST_METADATA_INVALID));
	}

	@Test
	void playlistMetadataMapsMalformedJsonToInvalidUpstreamMetadata() {
		server.enqueue(new MockResponse()
				.setResponseCode(200)
				.addHeader("Content-Type", "application/json")
				.setBody("{not-json"));

		assertThatThrownBy(() -> client.getPlaylistMetadata("37i9dQZF1DXcBWIGoYBM5M"))
				.isInstanceOfSatisfying(SoundConnectException.class,
						exception -> assertThat(exception.getErrorType())
								.isEqualTo(ErrorType.SPOTIFY_PLAYLIST_METADATA_INVALID));
	}

	@Test
	void playlistMetadataMapsAnUndecodableContentTypeToInvalidUpstreamMetadata() {
		server.enqueue(new MockResponse()
				.setResponseCode(200)
				.addHeader("Content-Type", "text/html")
				.setBody("<html>not oEmbed JSON</html>"));

		assertThatThrownBy(() -> client.getPlaylistMetadata("37i9dQZF1DXcBWIGoYBM5M"))
				.isInstanceOfSatisfying(SoundConnectException.class,
						exception -> assertThat(exception.getErrorType())
								.isEqualTo(ErrorType.SPOTIFY_PLAYLIST_METADATA_INVALID));
	}

	@Test
	void playlistMetadataMapsConfiguredBufferOverflowToInvalidUpstreamMetadata() {
		server.enqueue(new MockResponse()
				.setResponseCode(200)
				.addHeader("Content-Type", "application/json")
				.setBody("{\"title\":\"Playlist\","
						+ "\"thumbnail_url\":\"https://i.scdn.co/image/cover\","
						+ "\"padding\":\"" + "x".repeat(300_000) + "\"}"));

		assertThatThrownBy(() -> client.getPlaylistMetadata("37i9dQZF1DXcBWIGoYBM5M"))
				.isInstanceOfSatisfying(SoundConnectException.class,
						exception -> assertThat(exception.getErrorType())
								.isEqualTo(ErrorType.SPOTIFY_PLAYLIST_METADATA_INVALID));
	}

	@Test
	void playlistMetadataMapsAnUnavailablePublicPlaylistToNotFound() {
		server.enqueue(new MockResponse()
				.setResponseCode(404)
				.addHeader("Content-Type", "application/json")
				.setBody("{}"));

		assertThatThrownBy(() -> client.getPlaylistMetadata("37i9dQZF1DXcBWIGoYBM5M"))
				.isInstanceOfSatisfying(SoundConnectException.class,
						exception -> assertThat(exception.getErrorType())
								.isEqualTo(ErrorType.SPOTIFY_NOT_FOUND));
	}

	@Test
	void playlistMetadataPreservesSpotifyRetryAfterOnRateLimit() {
		server.enqueue(new MockResponse()
				.setResponseCode(429)
				.addHeader("Content-Type", "application/json")
				.addHeader("Retry-After", "23")
				.setBody("{}"));

		assertThatThrownBy(() -> client.getPlaylistMetadata("37i9dQZF1DXcBWIGoYBM5M"))
				.isInstanceOfSatisfying(RateLimitedException.class, exception -> {
					assertThat(exception.getErrorType()).isEqualTo(ErrorType.SPOTIFY_RATE_LIMITED);
					assertThat(exception.getRetryAfterSeconds()).isEqualTo(23L);
				});
	}

	@Test
	void playlistMetadataBatchRunsAtBoundedConcurrencyAndPreservesInputOrder() {
		List<String> ids = List.of(
				"37i9dQZF1DXcBWIGoYBM5M",
				"0vvXsWCC9xrXsKd4FyS8kM",
				"1111111111111111111111",
				"2222222222222222222222"
		);
		CountDownLatch allRequestsArrived = new CountDownLatch(ids.size());
		server.setDispatcher(new Dispatcher() {
			@Override
			public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
				allRequestsArrived.countDown();
				if (!allRequestsArrived.await(2, TimeUnit.SECONDS)) {
					return new MockResponse().setResponseCode(503);
				}
				String canonicalUrl = request.getRequestUrl().queryParameter("url");
				String id = canonicalUrl.substring(canonicalUrl.lastIndexOf('/') + 1);
				return new MockResponse()
						.setResponseCode(200)
						.addHeader("Content-Type", "application/json")
						.setBody("""
								{"title":"Playlist %s","thumbnail_url":"https://i.scdn.co/image/%s"}
								""".formatted(id, id));
			}
		});

		var result = client.getPlaylistMetadataBatch(ids);

		assertThat(result).extracting(SpotifyPlaylistMetadataDto::spotifyPlaylistId)
				.containsExactlyElementsOf(ids);
		assertThat(server.getRequestCount()).isEqualTo(4);
	}

	@Test
	void playlistMetadataMapsDedicatedPoolAcquireTimeout() {
		SpotifyProperties properties = new SpotifyProperties();
		properties.setApiBaseUrl(server.url("/").toString());
		properties.setOEmbedBaseUrl(server.url("/").toString());
		properties.getHttp().setResponseTimeoutMs(2_000);
		properties.getHttp().setReadTimeoutMs(2_000);
		// Deliberately bypass the validated production floor to deterministically
		// exercise the low-level Reactor pool-acquire timeout mapping.
		properties.getHttp().setOEmbedMaxConnections(1);
		properties.getHttp().setOEmbedPendingAcquireMaxCount(1);
		properties.getHttp().setOEmbedPendingAcquireTimeoutMs(50);
		SpotifyWebClientConfig webClientConfig = new SpotifyWebClientConfig(properties);
		ConnectionProvider connectionProvider = webClientConfig.spotifyOEmbedConnectionProvider();
		SpotifyTokenService tokenService = mock(SpotifyTokenService.class);
		SpotifyApiClientImpl poolBoundClient = new SpotifyApiClientImpl(
				properties,
				tokenService,
				webClientConfig.spotifyApiWebClient(),
				webClientConfig.spotifyOEmbedWebClient(connectionProvider)
		);
		server.enqueue(validPlaylistResponse("First")
				.setHeadersDelay(500, TimeUnit.MILLISECONDS));
		server.enqueue(validPlaylistResponse("Second"));

		try {
			assertThatThrownBy(() -> poolBoundClient.getPlaylistMetadataBatch(List.of(
					"37i9dQZF1DXcBWIGoYBM5M",
					"0vvXsWCC9xrXsKd4FyS8kM"
			)))
					.isInstanceOfSatisfying(SoundConnectException.class,
							exception -> assertThat(exception.getErrorType())
									.isEqualTo(ErrorType.SPOTIFY_TIMEOUT));
		} finally {
			connectionProvider.dispose();
		}
	}

	private MockResponse validPlaylistResponse(String title) {
		return new MockResponse()
				.setResponseCode(200)
				.addHeader("Content-Type", "application/json")
				.setBody("""
						{"title":"%s","thumbnail_url":"https://i.scdn.co/image/cover"}
						""".formatted(title));
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
