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
 * Distributed token-bucket policy for the listener visibility mutation route.
 *
 * <p>The bucket is deliberately short-lived: it protects database connections
 * and the listener-profile row lock from request floods without turning a
 * privacy preference into a long-lived product cooldown.</p>
 */
@Validated
@ConfigurationProperties(prefix = "app.listener-profile.visibility-rate-limit")
public class ListenerVisibilityRateLimitProperties {

	private boolean enabled = true;

	@NotBlank
	private String keyPrefix = "soundconnect:listener-profile:visibility-rate-limit";

	@Min(1)
	@Max(10)
	private int burstCapacity = 3;

	@NotNull
	private Duration refillPeriod = Duration.ofSeconds(10);

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

	public int getBurstCapacity() {
		return burstCapacity;
	}

	public void setBurstCapacity(int burstCapacity) {
		this.burstCapacity = burstCapacity;
	}

	public Duration getRefillPeriod() {
		return refillPeriod;
	}

	public void setRefillPeriod(Duration refillPeriod) {
		this.refillPeriod = refillPeriod;
	}

	@AssertTrue(message = "listener visibility rate-limit refill period must be between five seconds and one minute")
	public boolean isRefillPeriodValid() {
		return refillPeriod != null
				&& refillPeriod.compareTo(Duration.ofSeconds(5)) >= 0
				&& refillPeriod.compareTo(Duration.ofMinutes(1)) <= 0;
	}
}
