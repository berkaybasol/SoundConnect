package com.berkayb.soundconnect.modules.profile.ListenerProfile.abuse;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Distributed fixed-window policy protecting Spotify playlist metadata lookups.
 *
 * <p>Both limits are intentionally bounded so an external configuration source
 * cannot silently weaken production protection beyond ten requests per minute.
 * A longer window or a lower limit remains a safe operational override.</p>
 */
@Validated
@ConfigurationProperties(prefix = "app.listener-profile.playlist-rate-limit")
public class ListenerPlaylistRateLimitProperties {

	private boolean enabled = true;

	@NotBlank
	private String keyPrefix = "soundconnect:listener-profile:playlist-rate-limit";

	@Min(1)
	@Max(10)
	private int limit = 10;

	@NotNull
	private Duration window = Duration.ofMinutes(1);

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public String getKeyPrefix() {
		return keyPrefix;
	}

	public void setKeyPrefix(String keyPrefix) {
		this.keyPrefix = keyPrefix;
	}

	public int getLimit() {
		return limit;
	}

	public void setLimit(int limit) {
		this.limit = limit;
	}

	public Duration getWindow() {
		return window;
	}

	public void setWindow(Duration window) {
		this.window = window;
	}

	@AssertTrue(message = "listener playlist rate-limit window must be between one minute and one hour")
	public boolean isWindowValid() {
		return window != null
				&& window.compareTo(Duration.ofMinutes(1)) >= 0
				&& window.compareTo(Duration.ofHours(1)) <= 0;
	}
}
