package com.berkayb.soundconnect.modules.media.abuse;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "media.upload-guard")
public class MediaUploadGuardProperties {

	private boolean enabled = true;
	private boolean failOpen = false;

	@NotBlank
	private String keyPrefix = "soundconnect:media-upload";

	@Min(1)
	private int maxRequestsPerWindow = 20;

	@Min(1)
	private long maxBytesPerWindow = 8_000_000_000L;

	@NotNull
	private Duration window = Duration.ofHours(1);

	@Min(1)
	private int maxConcurrentUploads = 4;

	@NotNull
	private Duration reservationTtl = Duration.ofMinutes(30);

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public boolean isFailOpen() {
		return failOpen;
	}

	public void setFailOpen(boolean failOpen) {
		this.failOpen = failOpen;
	}

	public String getKeyPrefix() {
		return keyPrefix;
	}

	public void setKeyPrefix(String keyPrefix) {
		this.keyPrefix = keyPrefix;
	}

	public int getMaxRequestsPerWindow() {
		return maxRequestsPerWindow;
	}

	public void setMaxRequestsPerWindow(int maxRequestsPerWindow) {
		this.maxRequestsPerWindow = maxRequestsPerWindow;
	}

	public long getMaxBytesPerWindow() {
		return maxBytesPerWindow;
	}

	public void setMaxBytesPerWindow(long maxBytesPerWindow) {
		this.maxBytesPerWindow = maxBytesPerWindow;
	}

	public Duration getWindow() {
		return window;
	}

	public void setWindow(Duration window) {
		this.window = window;
	}

	public int getMaxConcurrentUploads() {
		return maxConcurrentUploads;
	}

	public void setMaxConcurrentUploads(int maxConcurrentUploads) {
		this.maxConcurrentUploads = maxConcurrentUploads;
	}

	public Duration getReservationTtl() {
		return reservationTtl;
	}

	public void setReservationTtl(Duration reservationTtl) {
		this.reservationTtl = reservationTtl;
	}

	@AssertTrue(message = "media upload guard durations must be at least one second")
	public boolean areDurationsValid() {
		return window != null
				&& reservationTtl != null
				&& window.compareTo(Duration.ofSeconds(1)) >= 0
				&& reservationTtl.compareTo(Duration.ofSeconds(1)) >= 0;
	}
}
