package com.berkayb.soundconnect.modules.spotify.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "soundconnect.spotify")
public class SpotifyProperties {
	
	private String clientId;
	private String clientSecret;
	private String tokenUrl;
	private String apiBaseUrl;
	private String market = "TR";
	
	private Http http = new Http();
	
	@Data
	public static class Http {
		private int connectTimeoutMs = 2000;
		private int responseTimeoutMs = 3500;
		private int readTimeoutMs = 3500;
		private int writeTimeoutMs = 3500;
	}
}