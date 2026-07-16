package com.berkayb.soundconnect.modules.media.storage;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Defines when a client-issued presigned PUT can no longer recreate an object
 * after cleanup. Storage deletion is deliberately deferred until this window
 * closes; deleting sooner would allow a late, still-valid PUT to resurrect an
 * untracked object after the database row is gone.
 */
@Component
public class PresignedUploadWriteWindow {
	/**
	 * S3 checks a presigned URL when the HTTP request starts. An accepted PUT can
	 * therefore finish after URL expiry. Keep the durable cleanup intent for a
	 * bounded transfer-completion window; 24h also covers a 4 GB upload at roughly
	 * 46 KB/s, well below a usable mobile upload rate.
	 */
	private static final Duration MINIMUM_IN_FLIGHT_COMPLETION_GRACE = Duration.ofHours(24);
	private static final Duration MAXIMUM_IN_FLIGHT_COMPLETION_GRACE = Duration.ofDays(7);
	private static final Duration LEGACY_MAXIMUM_WRITE_WINDOW =
			Duration.ofHours(1).plusMinutes(15).plus(MAXIMUM_IN_FLIGHT_COMPLETION_GRACE);

	private final Duration uploadAuthorityLifetime;
	private final Duration clockSkewAllowance;
	private final Duration inFlightCompletionGrace;
	private final Clock clock;

	@Autowired
	public PresignedUploadWriteWindow(
			@Value("${cloud.storage.presign.uploadExpirySeconds:${cloud.storage.presign.expirySeconds:900}}")
			int uploadExpirySeconds,
			@Value("${media.upload-cleanup.presign-clock-skew:PT1M}") Duration clockSkewAllowance,
			@Value("${media.upload-cleanup.in-flight-completion-grace:PT24H}")
			Duration inFlightCompletionGrace
	) {
		this(uploadExpirySeconds, clockSkewAllowance, inFlightCompletionGrace, Clock.systemUTC());
	}

	PresignedUploadWriteWindow(
			int uploadExpirySeconds,
			Duration clockSkewAllowance,
			Clock clock
	) {
		this(uploadExpirySeconds, clockSkewAllowance,
				MINIMUM_IN_FLIGHT_COMPLETION_GRACE, clock);
	}

	PresignedUploadWriteWindow(
			int uploadExpirySeconds,
			Duration clockSkewAllowance,
			Duration inFlightCompletionGrace,
			Clock clock
	) {
		if (uploadExpirySeconds < 60 || uploadExpirySeconds > 3600) {
			throw new IllegalArgumentException("uploadExpirySeconds must be between 60 and 3600");
		}
		if (clockSkewAllowance == null
				|| clockSkewAllowance.isNegative()
				|| clockSkewAllowance.compareTo(Duration.ofMinutes(15)) > 0) {
			throw new IllegalArgumentException("clockSkewAllowance must be between PT0S and PT15M");
		}
		if (inFlightCompletionGrace == null
				|| inFlightCompletionGrace.compareTo(MINIMUM_IN_FLIGHT_COMPLETION_GRACE) < 0
				|| inFlightCompletionGrace.compareTo(MAXIMUM_IN_FLIGHT_COMPLETION_GRACE) > 0) {
			throw new IllegalArgumentException(
					"inFlightCompletionGrace must be between PT24H and P7D");
		}
		this.uploadAuthorityLifetime = Duration.ofSeconds(uploadExpirySeconds);
		this.clockSkewAllowance = clockSkewAllowance;
		this.inFlightCompletionGrace = inFlightCompletionGrace;
		this.clock = clock;
	}

	/**
	 * Missing audit time fails closed: cleanup will retain the durable row rather
	 * than guess that write authority has expired.
	 */
	public boolean isSafeToDelete(LocalDateTime assetCreatedAt) {
		return assetCreatedAt != null && !clock.instant().isBefore(notBefore(assetCreatedAt));
	}

	/**
	 * Uses the exact per-row deadline when available. The created-at calculation
	 * remains only a conservative rolling-upgrade fallback for legacy rows.
	 */
	public boolean isSafeToDelete(
			LocalDateTime authorityExpiresAt,
			LocalDateTime legacyAssetCreatedAt
	) {
		if (authorityExpiresAt != null) {
			return !clock.instant().isBefore(
					authorityExpiresAt.toInstant(ZoneOffset.UTC).plus(inFlightCompletionGrace));
		}
		return isSafeToDelete(legacyAssetCreatedAt);
	}

	/** Call immediately after signing so the persisted deadline is conservative. */
	public LocalDateTime deadlineForNewSignature() {
		return LocalDateTime.ofInstant(
				clock.instant().plus(uploadAuthorityLifetime).plus(clockSkewAllowance),
				ZoneOffset.UTC
		);
	}

	public Instant notBefore(LocalDateTime assetCreatedAt) {
		if (assetCreatedAt == null) {
			throw new IllegalArgumentException("assetCreatedAt is required");
		}
		return assetCreatedAt.toInstant(ZoneOffset.UTC)
				.plus(LEGACY_MAXIMUM_WRITE_WINDOW);
	}
}
