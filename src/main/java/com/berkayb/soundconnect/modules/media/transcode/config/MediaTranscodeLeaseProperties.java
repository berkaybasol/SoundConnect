package com.berkayb.soundconnect.modules.media.transcode.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** Durable ownership and bounded crash-retry settings for HLS workers. */
@Data
@Validated
@Component
@ConfigurationProperties(prefix = "media.transcode.lease")
public class MediaTranscodeLeaseProperties {

	@NotNull
	private Duration duration = Duration.ofMinutes(15);

	@NotNull
	private Duration heartbeatInterval = Duration.ofMinutes(1);

	@NotNull
	private Duration retryBackoffBase = Duration.ofHours(1);

	@NotNull
	private Duration retryBackoffMax = Duration.ofHours(4);

	@Min(1)
	@Max(10)
	private int maxAttempts = 3;

	@AssertTrue(message = "media.transcode.lease duration and heartbeat interval are outside safe bounds")
	public boolean isHeartbeatWindowSafe() {
		if (duration == null || heartbeatInterval == null
				|| retryBackoffBase == null || retryBackoffMax == null) return false;
		return !heartbeatInterval.isNegative()
				&& !heartbeatInterval.isZero()
				&& heartbeatInterval.compareTo(Duration.ofSeconds(5)) >= 0
				&& duration.compareTo(Duration.ofMinutes(2)) >= 0
				&& duration.compareTo(Duration.ofHours(2)) <= 0
				&& duration.compareTo(heartbeatInterval.multipliedBy(3)) >= 0
				&& !retryBackoffBase.isNegative()
				&& !retryBackoffBase.isZero()
				&& retryBackoffBase.compareTo(Duration.ofMinutes(1)) >= 0
				&& retryBackoffMax.compareTo(retryBackoffBase) >= 0
				&& retryBackoffMax.compareTo(Duration.ofHours(24)) <= 0;
	}
}
