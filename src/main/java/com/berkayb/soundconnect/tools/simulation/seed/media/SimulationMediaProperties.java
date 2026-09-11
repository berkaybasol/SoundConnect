package com.berkayb.soundconnect.tools.simulation.seed.media;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Bounded delivery settings for the local simulation object store. */
@Validated
@ConfigurationProperties(prefix = "app.simulation.media")
public class SimulationMediaProperties {

	@NotBlank(message = "app.simulation.media.public-base-url is required")
	@Size(max = 512, message = "app.simulation.media.public-base-url is too long")
	private String publicBaseUrl = "http://10.0.2.2:8080";

	@Min(value = 1, message = "app.simulation.media.max-response-bytes must be positive")
	@Max(
			value = 67_108_864,
			message = "app.simulation.media.max-response-bytes must not exceed 64 MiB"
	)
	private long maxResponseBytes = 8L * 1024L * 1024L;

	@Min(value = 1, message = "app.simulation.media.max-public-capabilities must be positive")
	@Max(
			value = 20_000,
			message = "app.simulation.media.max-public-capabilities must not exceed 20000"
	)
	private int maxPublicCapabilities = 4_096;

	public String getPublicBaseUrl() {
		return publicBaseUrl;
	}

	public void setPublicBaseUrl(String publicBaseUrl) {
		this.publicBaseUrl = publicBaseUrl;
	}

	public long getMaxResponseBytes() {
		return maxResponseBytes;
	}

	public void setMaxResponseBytes(long maxResponseBytes) {
		this.maxResponseBytes = maxResponseBytes;
	}

	public int getMaxPublicCapabilities() {
		return maxPublicCapabilities;
	}

	public void setMaxPublicCapabilities(int maxPublicCapabilities) {
		this.maxPublicCapabilities = maxPublicCapabilities;
	}

	@AssertTrue(message = "app.simulation.media.public-base-url must be an HTTP(S) origin")
	public boolean isPublicBaseUrlValid() {
		try {
			SimulationFileStorageClient.validatePublicBaseUrl(publicBaseUrl);
			return true;
		} catch (IllegalArgumentException invalid) {
			return false;
		}
	}

	String normalizedPublicBaseUrl() {
		return SimulationFileStorageClient.validatePublicBaseUrl(publicBaseUrl);
	}
}
