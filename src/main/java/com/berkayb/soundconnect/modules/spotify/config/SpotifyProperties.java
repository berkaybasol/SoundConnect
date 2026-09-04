package com.berkayb.soundconnect.modules.spotify.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@ConfigurationProperties(prefix = "soundconnect.spotify")
public class SpotifyProperties {
	
	private String clientId;
	private String clientSecret;
	private String tokenUrl;
	private String apiBaseUrl;
	private String oEmbedBaseUrl = "https://open.spotify.com";
	private String market = "TR";
	
	@Valid
	@NotNull
	private Http http = new Http();
	
	@Data
	public static class Http {
		@Min(1)
		private int connectTimeoutMs = 2000;
		@Min(1)
		private int responseTimeoutMs = 3500;
		@Min(1)
		private int readTimeoutMs = 3500;
		@Min(1)
		private int writeTimeoutMs = 3500;

		/** Dedicated pool capacity for the public Spotify oEmbed integration. */
		@Min(4)
		@Max(64)
		private int oEmbedMaxConnections = 32;

		/** Bound callers waiting for an oEmbed connection instead of growing an unbounded queue. */
		@Min(1)
		@Max(256)
		private int oEmbedPendingAcquireMaxCount = 64;

		@Min(1)
		private int oEmbedPendingAcquireTimeoutMs = 750;

		/** oEmbed is tiny JSON; cap buffering so a bad upstream response cannot consume the heap. */
		@Min(4096)
		@Max(262144)
		private int oEmbedMaxResponseBytes = 65536;

		@AssertTrue(message = "Spotify oEmbed pool acquire timeout must not exceed response timeout")
		public boolean isPendingAcquireTimeoutBounded() {
			return oEmbedPendingAcquireTimeoutMs <= responseTimeoutMs;
		}
	}
}
