package com.berkayb.soundconnect.modules.spotify.service;

import com.berkayb.soundconnect.modules.spotify.config.SpotifyProperties;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

@Slf4j
@Service

public class SpotifyTokenServiceImpl implements SpotifyTokenService {
	
	private static final String TOKEN_KEY = "spotify:access_token";
	private static final String LOCK_KEY = "spotify:access_token:lock";
	
	private final SpotifyProperties props;
	private final StringRedisTemplate redisTemplate;
	private final WebClient spotifyTokenWebClient;
	
	public SpotifyTokenServiceImpl(
			SpotifyProperties props,
			StringRedisTemplate redisTemplate,
			@Qualifier("spotifyTokenWebClient") WebClient spotifyTokenWebClient
	) {
		this.props = props;
		this.redisTemplate = redisTemplate;
		this.spotifyTokenWebClient = spotifyTokenWebClient;
	}
	
	@Override
	public String getAccessToken() {
		String cached = redisTemplate.opsForValue().get(TOKEN_KEY);
		if (cached != null && !cached.isBlank()) {
			return cached;
		}
		
		String lockValue = UUID.randomUUID().toString();
		boolean lockAcquired = tryAcquireLock(lockValue, Duration.ofSeconds(10));
		
		if (!lockAcquired) {
			for (int i = 0; i < 5; i++) {
				sleepSilently(150);
				String retryCache = redisTemplate.opsForValue().get(TOKEN_KEY);
				if (retryCache != null && !retryCache.isBlank()) {
					return retryCache;
				}
			}
			
			throw new SoundConnectException(ErrorType.SPOTIFY_UPSTREAM_ERROR);
		}
		
		try {
			String retryCache = redisTemplate.opsForValue().get(TOKEN_KEY);
			if (retryCache != null && !retryCache.isBlank()) {
				return retryCache;
			}
			
			log.info("[Spotify] Access token not found/expired. Requesting new token...");
			
			String credentials = props.getClientId() + ":" + props.getClientSecret();
			String encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
			
			SpotifyTokenResponse response = spotifyTokenWebClient.post()
			                                                     .uri(props.getTokenUrl())
			                                                     .header(HttpHeaders.AUTHORIZATION, "Basic " + encoded)
			                                                     .contentType(MediaType.APPLICATION_FORM_URLENCODED)
			                                                     .body(BodyInserters.fromFormData("grant_type", "client_credentials"))
			                                                     .retrieve()
			                                                     .bodyToMono(SpotifyTokenResponse.class)
			                                                     .block();
			
			if (response == null || response.access_token() == null || response.access_token().isBlank()) {
				log.error("[Spotify] Token response is null or access_token missing");
				throw new SoundConnectException(ErrorType.SPOTIFY_AUTH_FAILED);
			}
			
			int expires = response.expires_in() == null ? 3600 : response.expires_in();
			int ttl = Math.max(60, expires - 60);
			
			redisTemplate.opsForValue().set(TOKEN_KEY, response.access_token(), Duration.ofSeconds(ttl));
			return response.access_token();
			
		} catch (WebClientResponseException ex) {
			int status = ex.getStatusCode().value();
			String retryAfter = ex.getHeaders().getFirst("Retry-After");
			
			log.error("[Spotify] Token endpoint error status={}, retryAfter={}, body={}",
			          status,
			          retryAfter == null ? "-" : retryAfter,
			          safeBody(ex));
			
			if (status == 401 || status == 403) throw new SoundConnectException(ErrorType.SPOTIFY_AUTH_FAILED);
			if (status == 429) throw new SoundConnectException(ErrorType.SPOTIFY_RATE_LIMITED);
			if (status >= 400 && status < 500) throw new SoundConnectException(ErrorType.SPOTIFY_BAD_REQUEST);
			if (status >= 500) throw new SoundConnectException(ErrorType.SPOTIFY_UPSTREAM_ERROR);
			
			throw new SoundConnectException(ErrorType.SPOTIFY_UNEXPECTED_ERROR);
			
		} catch (SoundConnectException ex) {
			throw ex;
		} catch (Exception ex) {
			log.error("[Spotify] Unexpected error while requesting token", ex);
			throw new SoundConnectException(ErrorType.SPOTIFY_UNEXPECTED_ERROR);
		} finally {
			if (lockAcquired) {
				releaseLock(lockValue);
			}
		}
	}
	
	private boolean tryAcquireLock(String lockValue, Duration ttl) {
		Boolean ok = redisTemplate.opsForValue().setIfAbsent(LOCK_KEY, lockValue, ttl);
		return Boolean.TRUE.equals(ok);
	}
	
	private void releaseLock(String lockValue) {
		try {
			String currentValue = redisTemplate.opsForValue().get(LOCK_KEY);
			if (lockValue != null && lockValue.equals(currentValue)) {
				redisTemplate.delete(LOCK_KEY);
			}
		} catch (Exception ex) {
			log.warn("[Spotify] Failed to release token lock safely", ex);
		}
	}
	
	private void sleepSilently(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
	
	private String safeBody(WebClientResponseException ex) {
		String body = ex.getResponseBodyAsString();
		return (body == null || body.isBlank()) ? "-" : body;
	}
	
	private record SpotifyTokenResponse(String access_token, Integer expires_in) { }
}