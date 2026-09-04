package com.berkayb.soundconnect.modules.spotify.client;

import com.berkayb.soundconnect.modules.spotify.config.SpotifyProperties;
import com.berkayb.soundconnect.modules.spotify.dto.response.SpotifyTrackItemDto;
import com.berkayb.soundconnect.modules.spotify.dto.response.SpotifyPlaylistMetadataDto;
import com.berkayb.soundconnect.modules.spotify.service.SpotifyTokenService;
import com.berkayb.soundconnect.modules.spotify.support.SpotifyPlaylistMetadataPolicy;
import com.berkayb.soundconnect.modules.spotify.support.SpotifyPlaylistUrlParser;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.RateLimitedException;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.codec.CodecException;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.SocketTimeoutException;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeoutException;

@Slf4j
@Component
public class SpotifyApiClientImpl implements SpotifyApiClient {
	private static final int MAX_PLAYLIST_METADATA_BATCH_SIZE = 4;
	
	private final SpotifyProperties props;
	private final SpotifyTokenService tokenService;
	private final WebClient spotifyApiWebClient;
	private final WebClient spotifyOEmbedWebClient;
	
	public SpotifyApiClientImpl(
			SpotifyProperties props,
			SpotifyTokenService tokenService,
			@Qualifier("spotifyApiWebClient") WebClient spotifyApiWebClient,
			@Qualifier("spotifyOEmbedWebClient") WebClient spotifyOEmbedWebClient
	) {
		this.props = props;
		this.tokenService = tokenService;
		this.spotifyApiWebClient = spotifyApiWebClient;
		this.spotifyOEmbedWebClient = spotifyOEmbedWebClient;
	}

	@Override
	public SpotifyPlaylistMetadataDto getPlaylistMetadata(String spotifyPlaylistId) {
		SpotifyPlaylistUrlParser.canonicalUrl(spotifyPlaylistId);
		return getPlaylistMetadataBatch(List.of(spotifyPlaylistId)).getFirst();
	}

	@Override
	public List<SpotifyPlaylistMetadataDto> getPlaylistMetadataBatch(
			List<String> spotifyPlaylistIds
	) {
		if (spotifyPlaylistIds == null
				|| spotifyPlaylistIds.size() > MAX_PLAYLIST_METADATA_BATCH_SIZE) {
			throw new SoundConnectException(ErrorType.SPOTIFY_BAD_REQUEST);
		}
		if (spotifyPlaylistIds.isEmpty()) {
			return List.of();
		}
		List<String> validatedIds = spotifyPlaylistIds.stream()
				.peek(SpotifyPlaylistUrlParser::canonicalUrl)
				.toList();
		if (validatedIds.stream().distinct().count() != validatedIds.size()) {
			throw new SoundConnectException(ErrorType.SPOTIFY_BAD_REQUEST);
		}

		List<SpotifyPlaylistMetadataDto> snapshots = Flux.fromIterable(validatedIds)
				.flatMapSequential(
						this::requestPlaylistMetadata,
						MAX_PLAYLIST_METADATA_BATCH_SIZE,
						1
				)
				.collectList()
				.block();
		if (snapshots == null || snapshots.size() != validatedIds.size()) {
			throw new SoundConnectException(ErrorType.SPOTIFY_PLAYLIST_METADATA_INVALID);
		}
		return List.copyOf(snapshots);
	}

	private Mono<SpotifyPlaylistMetadataDto> requestPlaylistMetadata(String spotifyPlaylistId) {
		String canonicalUrl = SpotifyPlaylistUrlParser.canonicalUrl(spotifyPlaylistId);
		return spotifyOEmbedWebClient.get()
				.uri(uriBuilder -> uriBuilder
						.path("/oembed")
						.queryParam("url", canonicalUrl)
						.build())
				.retrieve()
				.bodyToMono(SpotifyOEmbedRawResponse.class)
				.switchIfEmpty(Mono.error(
						new SoundConnectException(ErrorType.SPOTIFY_PLAYLIST_METADATA_INVALID)))
				.map(raw -> SpotifyPlaylistMetadataPolicy.validateAndNormalize(
						new SpotifyPlaylistMetadataDto(
								spotifyPlaylistId,
								raw.title(),
								raw.thumbnail_url(),
								canonicalUrl
						)))
				.onErrorMap(
						this::hasInvalidPayloadCause,
						exception -> invalidSpotifyOEmbedPayload(spotifyPlaylistId, exception)
				)
				.onErrorMap(WebClientResponseException.class,
						exception -> spotifyOEmbedHttpError(spotifyPlaylistId, exception))
				.onErrorMap(WebClientRequestException.class,
						exception -> spotifyOEmbedTransportError(spotifyPlaylistId, exception))
				.onErrorMap(
						exception -> !(exception instanceof SoundConnectException)
								&& hasTimeoutCause(exception),
						exception -> spotifyOEmbedTimeout(spotifyPlaylistId)
				)
				.onErrorMap(
						exception -> !(exception instanceof SoundConnectException),
						exception -> unexpectedSpotifyOEmbedError(spotifyPlaylistId, exception)
				);
	}
	
	@Override
	public List<SpotifyTrackItemDto> getTracksByIds(List<String> trackIds) {
		if (trackIds == null || trackIds.isEmpty()) return List.of();

		List<String> ids = trackIds.stream()
		                            .filter(id -> id != null && !id.isBlank())
		                            .map(String::strip)
		                            .distinct()
		                            .limit(50)
		                            .toList();

		if (ids.isEmpty()) return List.of();
		
		String token = tokenService.getAccessToken();
		
		try {
			// Spotify deprecated and removed the batch GET /tracks endpoint from
			// Development Mode in February 2026. Fetching each authoritative
			// snapshot individually keeps client-supplied metadata untrusted while
			// preserving the caller's requested catalog order.
			return ids.stream()
			          .map(id -> fetchTrackById(id, token))
			          .toList();
			
		} catch (WebClientResponseException ex) {
			throw spotifyHttpError(ex);
		} catch (Exception ex) {
			log.error("[Spotify] Unexpected error while fetching tracks by ids. ids={}", ids, ex);
			throw new SoundConnectException(ErrorType.SPOTIFY_UNEXPECTED_ERROR);
		}
	}
	
	
	@Override
	public List<SpotifyTrackItemDto> searchTracks(String query, int limit) {
		
		String token = tokenService.getAccessToken();
		
		try {
			SpotifySearchRawResponse raw = spotifyApiWebClient.get()
			                                                  .uri(uriBuilder -> uriBuilder
					                                                  .path("/search")
					                                                  .queryParam("q", query)
					                                                  .queryParam("type", "track")
					                                                  .queryParam("limit", Math.min(limit, 10)) // Spotify limitleri sıkılaştı
					                                                  .queryParam("market", props.getMarket())
					                                                  .build())
			                                                  .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
			                                                  .retrieve()
			                                                  .bodyToMono(SpotifySearchRawResponse.class)
			                                                  .block();
			
			if (raw == null || raw.tracks == null || raw.tracks.items == null) {
				return List.of();
			}
			
			return raw.tracks.items.stream()
			                       .map(this::toTrackItemDto)
			                       .toList();
			
		} catch (WebClientResponseException ex) {
			throw spotifyHttpError(ex);
		} catch (Exception ex) {
			log.error("[Spotify] Unexpected error while searching tracks. query={}, limit={}", query, limit, ex);
			throw new SoundConnectException(ErrorType.SPOTIFY_UNEXPECTED_ERROR);
		}
	}
	
	
	@Override
	public SpotifyTrackItemDto getTrackById(String trackId) {
		
		String token = tokenService.getAccessToken();
		
		try {
			return fetchTrackById(trackId, token);
			
		}  catch (WebClientResponseException ex) {
			throw spotifyHttpError(ex);
		} catch (Exception ex) {
			log.error("[Spotify] Unexpected error while fetching track detail. trackId={}", trackId, ex);
			throw new SoundConnectException(ErrorType.SPOTIFY_UNEXPECTED_ERROR);
		}
	}

	private SpotifyTrackItemDto fetchTrackById(String trackId, String token) {
		SpotifyTrackRaw track = spotifyApiWebClient.get()
		                                           .uri(uriBuilder -> uriBuilder
				                                           .path("/tracks/{id}")
				                                           .queryParam("market", props.getMarket())
				                                           .build(trackId))
		                                           .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
		                                           .retrieve()
		                                           .bodyToMono(SpotifyTrackRaw.class)
		                                           .block();
		if (track == null) {
			throw new SoundConnectException(ErrorType.SPOTIFY_NOT_FOUND);
		}
		return toTrackItemDto(track);
	}
	
	
	/* ---------- Mapping: Raw -> DTO ---------- */
	
	private SpotifyTrackItemDto toTrackItemDto(SpotifyTrackRaw t) {
		return new SpotifyTrackItemDto(
				t.id,
				t.name,
				t.duration_ms,
				Boolean.TRUE.equals(t.explicit),
				t.preview_url,
				(t.external_urls != null ? t.external_urls.spotify : null),
				(t.album != null ? t.album.name : null),
				bestAlbumImageUrl(t.album),
				(t.artists == null ? List.of() : t.artists.stream().map(a -> a.name).toList())
		);
	}
	
	private String bestAlbumImageUrl(SpotifyAlbum album) {
		if (album == null || album.images == null || album.images.isEmpty()) return null;
		
		
		return album.images.stream()
		                   .max(Comparator.comparingInt(i -> (i.width == null ? 0 : i.width)))
		                   .map(i -> i.url)
		                   .orElse(null);
	}
	
	private String safeBody(WebClientResponseException ex) {
		String b = ex.getResponseBodyAsString();
		if (b == null || b.isBlank()) return "-";
		String singleLine = b.replace('\r', ' ').replace('\n', ' ');
		return singleLine.length() <= 512 ? singleLine : singleLine.substring(0, 512);
	}
	
	
	
	private record SpotifySearchRawResponse(SpotifyTracksItemsWrapper tracks) { }
	
	private record SpotifyTrackRaw(
			String id,
			String name,
			Integer duration_ms,
			Boolean explicit,
			String preview_url,
			SpotifyAlbum album,
			List<SpotifyArtistRaw> artists,
			SpotifyExternalUrls external_urls
	) { }
	
	private record SpotifyAlbum(String name, List<SpotifyImage> images) {}
	
	private record SpotifyImage(String url, Integer height, Integer width) {}
	
	private record SpotifyArtistRaw(String id, String name) {}
	
	private record SpotifyExternalUrls(String spotify) {}

	private record SpotifyOEmbedRawResponse(
			String title,
			String thumbnail_url,
			Integer thumbnail_width,
			Integer thumbnail_height
	) { }

	private boolean hasTimeoutCause(Throwable throwable) {
		Throwable current = throwable;
		while (current != null) {
			if (current instanceof TimeoutException || current instanceof SocketTimeoutException
					|| current.getClass().getSimpleName().contains("Timeout")) {
				return true;
			}
			current = current.getCause();
		}
		return false;
	}

	private boolean hasInvalidPayloadCause(Throwable throwable) {
		Throwable current = throwable;
		while (current != null) {
			if (current instanceof CodecException || current instanceof DataBufferLimitException) {
				return true;
			}
			current = current.getCause();
		}
		return false;
	}
	
	private SoundConnectException spotifyHttpError(WebClientResponseException ex) {
		int status = ex.getStatusCode().value();
		
		log.error("[Spotify] API error status={}, retryAfter={}, body={}",
		          status,
		          ex.getHeaders().getFirst("Retry-After"),
		          safeBody(ex));
		
		if (status == 401) {
			return new SoundConnectException(ErrorType.SPOTIFY_AUTH_FAILED);
		}
		
		if (status == 403) {
			return new SoundConnectException(ErrorType.SPOTIFY_FORBIDDEN);
		}
		
		if (status == 404) {
			return new SoundConnectException(ErrorType.SPOTIFY_NOT_FOUND);
		}
		
		if (status == 429) {
			return new RateLimitedException(
					ErrorType.SPOTIFY_RATE_LIMITED,
					parseRetryAfterSeconds(ex.getHeaders().getFirst("Retry-After"))
			);
		}
		
		if (status >= 400 && status < 500) {
			return new SoundConnectException(ErrorType.SPOTIFY_BAD_REQUEST);
		}
		
		if (status >= 500) {
			return new SoundConnectException(ErrorType.SPOTIFY_UPSTREAM_ERROR);
		}
		
		return new SoundConnectException(ErrorType.SPOTIFY_UNEXPECTED_ERROR);
	}

	private SoundConnectException spotifyOEmbedHttpError(
			String spotifyPlaylistId,
			WebClientResponseException exception
	) {
		if (exception.getStatusCode().is2xxSuccessful()) {
			return invalidSpotifyOEmbedPayload(spotifyPlaylistId, exception);
		}
		return spotifyHttpError(exception);
	}

	private SoundConnectException spotifyOEmbedTransportError(
			String spotifyPlaylistId,
			WebClientRequestException exception
	) {
		if (hasTimeoutCause(exception)) {
			log.warn("[Spotify] oEmbed request timed out. playlistId={}", spotifyPlaylistId);
			return new SoundConnectException(ErrorType.SPOTIFY_TIMEOUT);
		}
		log.error("[Spotify] oEmbed transport error. playlistId={}", spotifyPlaylistId, exception);
		return new SoundConnectException(ErrorType.SPOTIFY_UPSTREAM_ERROR);
	}

	private SoundConnectException spotifyOEmbedTimeout(String spotifyPlaylistId) {
		log.warn("[Spotify] oEmbed request timed out. playlistId={}", spotifyPlaylistId);
		return new SoundConnectException(ErrorType.SPOTIFY_TIMEOUT);
	}

	private SoundConnectException unexpectedSpotifyOEmbedError(
			String spotifyPlaylistId,
			Throwable exception
	) {
		log.error("[Spotify] Unexpected oEmbed error. playlistId={}", spotifyPlaylistId, exception);
		return new SoundConnectException(ErrorType.SPOTIFY_UPSTREAM_ERROR);
	}

	private SoundConnectException invalidSpotifyOEmbedPayload(
			String spotifyPlaylistId,
			Throwable exception
	) {
		log.warn("[Spotify] Invalid oEmbed payload. playlistId={}, cause={}",
				spotifyPlaylistId,
				exception.getClass().getSimpleName());
		return new SoundConnectException(ErrorType.SPOTIFY_PLAYLIST_METADATA_INVALID);
	}

	private long parseRetryAfterSeconds(String value) {
		if (value == null || value.isBlank()) return 5L;
		try {
			return Math.max(1L, Math.min(Long.parseLong(value.strip()), 3600L));
		} catch (NumberFormatException exception) {
			return 5L;
		}
	}
	
	private record SpotifyTracksItemsWrapper(List<SpotifyTrackRaw> items) { }
	
}
