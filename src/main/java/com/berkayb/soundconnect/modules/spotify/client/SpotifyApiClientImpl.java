package com.berkayb.soundconnect.modules.spotify.client;

import com.berkayb.soundconnect.modules.spotify.config.SpotifyProperties;
import com.berkayb.soundconnect.modules.spotify.dto.response.SpotifyTrackItemDto;
import com.berkayb.soundconnect.modules.spotify.service.SpotifyTokenService;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.Comparator;
import java.util.List;

@Slf4j
@Component
public class SpotifyApiClientImpl implements SpotifyApiClient {
	
	private final SpotifyProperties props;
	private final SpotifyTokenService tokenService;
	private final WebClient spotifyApiWebClient;
	
	public SpotifyApiClientImpl(
			SpotifyProperties props,
			SpotifyTokenService tokenService,
			@Qualifier("spotifyApiWebClient") WebClient spotifyApiWebClient
	) {
		this.props = props;
		this.tokenService = tokenService;
		this.spotifyApiWebClient = spotifyApiWebClient;
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
			handleSpotifyHttpError(ex);
			throw new IllegalStateException("Unreachable code after Spotify HTTP error handling");
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
			handleSpotifyHttpError(ex);
			throw new IllegalStateException("Unreachable code after Spotify HTTP error handling");
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
			handleSpotifyHttpError(ex);
			throw new IllegalStateException("Unreachable code after Spotify HTTP error handling");
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
		return (b == null || b.isBlank()) ? "-" : b;
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
	
	private void handleSpotifyHttpError(WebClientResponseException ex) {
		int status = ex.getStatusCode().value();
		
		log.error("[Spotify] API error status={}, retryAfter={}, body={}",
		          status,
		          ex.getHeaders().getFirst("Retry-After"),
		          safeBody(ex));
		
		if (status == 401) {
			throw new SoundConnectException(ErrorType.SPOTIFY_AUTH_FAILED);
		}
		
		if (status == 403) {
			throw new SoundConnectException(ErrorType.SPOTIFY_FORBIDDEN);
		}
		
		if (status == 404) {
			throw new SoundConnectException(ErrorType.SPOTIFY_NOT_FOUND);
		}
		
		if (status == 429) {
			throw new SoundConnectException(ErrorType.SPOTIFY_RATE_LIMITED);
		}
		
		if (status >= 400 && status < 500) {
			throw new SoundConnectException(ErrorType.SPOTIFY_BAD_REQUEST);
		}
		
		if (status >= 500) {
			throw new SoundConnectException(ErrorType.SPOTIFY_UPSTREAM_ERROR);
		}
		
		throw new SoundConnectException(ErrorType.SPOTIFY_UNEXPECTED_ERROR);
	}
	
	private record SpotifyTracksItemsWrapper(List<SpotifyTrackRaw> items) { }
	
}
