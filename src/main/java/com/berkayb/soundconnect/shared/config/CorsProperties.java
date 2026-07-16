package com.berkayb.soundconnect.shared.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Browser ve WebSocket origin allowlist'i. Credentials desteklenmedigi icin
 * istemciler kimligi Authorization bearer token ile tasir.
 */
@ConfigurationProperties(prefix = "app.cors")
public class CorsProperties {

	private List<String> allowedOriginPatterns = new ArrayList<>(List.of(
			"http://localhost:*",
			"http://127.0.0.1:*"
	));

	public List<String> getAllowedOriginPatterns() {
		return List.copyOf(allowedOriginPatterns);
	}

	public void setAllowedOriginPatterns(List<String> allowedOriginPatterns) {
		this.allowedOriginPatterns = allowedOriginPatterns == null
				? new ArrayList<>()
				: new ArrayList<>(allowedOriginPatterns);
	}

	public List<String> requireSafeAllowedOriginPatterns() {
		List<String> normalized = allowedOriginPatterns.stream()
				.filter(pattern -> pattern != null && !pattern.isBlank())
				.map(String::trim)
				.toList();
		if (normalized.isEmpty() || normalized.contains("*")) {
			throw new IllegalStateException(
					"app.cors.allowed-origin-patterns must be a non-empty explicit allowlist"
			);
		}
		return normalized;
	}
}
