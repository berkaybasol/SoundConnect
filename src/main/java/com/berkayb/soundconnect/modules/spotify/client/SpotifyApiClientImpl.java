package com.berkayb.soundconnect.modules.spotify.client;

import com.berkayb.soundconnect.modules.spotify.config.SpotifyProperties;
import com.berkayb.soundconnect.modules.spotify.dto.response.SpotifyArtistTopTrackResponseDto;
import com.berkayb.soundconnect.modules.spotify.dto.response.SpotifyTrackItemDto;
import com.berkayb.soundconnect.modules.spotify.service.SpotifyTokenService;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.Comparator;
import java.util.List;

@Slf4j
@RequiredArgsConstructor
@Component
public class SpotifyApiClientImpl implements SpotifyApiClient {
	
	
	private final SpotifyProperties props;
	private final SpotifyTokenService tokenService;
	private final WebClient spotifyApiWebClient;
	
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
			return List.of(); // unreachable
		} catch (Exception ex) {
			log.error("[Spotify] Unexpected error while searching tracks", ex);
			throw new SoundConnectException(ErrorType.SPOTIFY_UNEXPECTED_ERROR);
		}
	}
	
	
	@Override
	public SpotifyTrackItemDto getTrackById(String trackId) {
		
		String token = tokenService.getAccessToken();
		
		try {
			SpotifyTrackRaw t = spotifyApiWebClient.get()
			                                       .uri("/tracks/{id}", trackId)
			                                       .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
			                                       .retrieve()
			                                       .bodyToMono(SpotifyTrackRaw.class)
			                                       .block();
			
			if (t == null) {
				throw new SoundConnectException(ErrorType.SPOTIFY_NOT_FOUND);
			}
			
			return toTrackItemDto(t);
			
		} catch (WebClientResponseException ex) {
			handleSpotifyHttpError(ex);
			return null; // unreachable
		} catch (Exception ex) {
			log.error("[Spotify] Unexpected error while fetching track detail", ex);
			throw new SoundConnectException(ErrorType.SPOTIFY_UNEXPECTED_ERROR);
		}
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
	
	@Override
	public SpotifyArtistTopTrackResponseDto getArtistTopTracks(String artistId) {
		String token = tokenService.getAccessToken();
		
		try {
			SpotifyTopTracksRawResponse raw = spotifyApiWebClient.get()
			                                                     .uri(uriBuilder -> uriBuilder
					                                                     .path("/artists/{id}/top-tracks")
					                                                     .queryParam("market", props.getMarket())
					                                                     .build(artistId))
			                                                     .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
			                                                     .accept(MediaType.APPLICATION_JSON)
			                                                     .retrieve()
			                                                     .bodyToMono(SpotifyTopTracksRawResponse.class)
			                                                     .block();
			
			if (raw == null || raw.tracks == null) {
				return new SpotifyArtistTopTrackResponseDto(artistId, props.getMarket(), List.of());
			}
			
			List<SpotifyTrackItemDto> tracks = raw.tracks.stream()
			                                             .map(t -> new SpotifyTrackItemDto(
					                                             t.id,
					                                             t.name,
					                                             t.duration_ms,
					                                             Boolean.TRUE.equals(t.explicit),
					                                             t.preview_url,
					                                             (t.external_urls != null ? t.external_urls.spotify : null),
					                                             (t.album != null ? t.album.name : null),
					                                             bestAlbumImageUrl(t.album),
					                                             (t.artists == null ? List.of() : t.artists.stream().map(a -> a.name).toList())
			                                             ))
			                                             .toList();
			
			return new SpotifyArtistTopTrackResponseDto(artistId, props.getMarket(), tracks);
			
		} catch (WebClientResponseException ex) {
			int status = ex.getStatusCode().value();
			String retryAfter = ex.getHeaders().getFirst("Retry-After");
			
			log.error("[Spotify] API error status={}, retryAfter={}, body={}",
			          status, (retryAfter == null ? "-" : retryAfter), safeBody(ex));
			
			if (status == 401 || status == 403) throw new SoundConnectException(ErrorType.SPOTIFY_AUTH_FAILED);
			if (status == 404) throw new SoundConnectException(ErrorType.SPOTIFY_NOT_FOUND);
			if (status == 429) throw new SoundConnectException(ErrorType.SPOTIFY_RATE_LIMITED);
			if (status >= 400 && status < 500) throw new SoundConnectException(ErrorType.SPOTIFY_BAD_REQUEST);
			if (status >= 500) throw new SoundConnectException(ErrorType.SPOTIFY_UPSTREAM_ERROR);
			
			throw new SoundConnectException(ErrorType.SPOTIFY_UNEXPECTED_ERROR);
			
		} catch (Exception ex) {
			log.error("[Spotify] Unexpected error while calling Spotify API", ex);
			throw new SoundConnectException(ErrorType.SPOTIFY_UNEXPECTED_ERROR);
		}
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
	
	private record SpotifyTopTracksRawResponse(List<SpotifyTrackRaw> tracks) { }
	
	private record SpotifyTracksWrapper(List<SpotifyTrackRaw> items) { }
	
	private record SpotifySearchRawResponse(SpotifyTracksWrapper tracks) { }
	
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
	
}