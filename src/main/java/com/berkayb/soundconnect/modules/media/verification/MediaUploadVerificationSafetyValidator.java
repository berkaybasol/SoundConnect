package com.berkayb.soundconnect.modules.media.verification;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Keeps the operational timing contract executable. An expired lease may start
 * a second cross-node worker, so it must outlive the fully bounded storage path
 * plus one recovery cadence and a small DB/scheduling margin.
 */
@Component
@RequiredArgsConstructor
public class MediaUploadVerificationSafetyValidator {

	private static final int MAX_STORAGE_CALLS_PER_ATTEMPT = 5;
	private static final Duration DB_AND_SCHEDULING_MARGIN = Duration.ofSeconds(30);
	private static final Duration MIN_REQUEST_WAIT = Duration.ofSeconds(3);
	private static final Duration MAX_REQUEST_WAIT = Duration.ofSeconds(5);

	private final MediaUploadVerificationProperties properties;

	@PostConstruct
	public void validate() {
		Duration requestWait = requirePositive(
				properties.getRequestWaitTimeout(),
				"media.upload-verification.request-wait-timeout");
		if (requestWait.compareTo(MIN_REQUEST_WAIT) < 0
				|| requestWait.compareTo(MAX_REQUEST_WAIT) > 0) {
			throw new IllegalStateException(
					"media.upload-verification.request-wait-timeout must be between PT3S and PT5S");
		}

		Duration storageCallTimeout = requirePositive(
				properties.getStorageCallTimeout(),
				"media.upload-verification.storage-call-timeout");
		Duration lease = requirePositive(
				properties.getVerificationLease(),
				"media.upload-verification.verification-lease");
		Duration requiredLease = storageCallTimeout
				.multipliedBy(MAX_STORAGE_CALLS_PER_ATTEMPT)
				.plusMillis(Math.max(1_000L, properties.getRecoveryFixedDelayMs()))
				.plus(DB_AND_SCHEDULING_MARGIN);
		if (lease.compareTo(requiredLease) < 0) {
			throw new IllegalStateException(
					"media.upload-verification.verification-lease must be at least "
							+ requiredLease
							+ " for the configured storage timeout and recovery cadence");
		}

		Duration hardTimeout = requirePositive(
				properties.getAttemptHardTimeout(),
				"media.upload-verification.attempt-hard-timeout");
		Duration requiredHardTimeout = lease
				.plus(storageCallTimeout)
				.plus(DB_AND_SCHEDULING_MARGIN);
		if (hardTimeout.compareTo(requiredHardTimeout) < 0) {
			throw new IllegalStateException(
					"media.upload-verification.attempt-hard-timeout must be at least "
							+ requiredHardTimeout);
		}
	}

	private static Duration requirePositive(Duration value, String property) {
		if (value == null || value.isZero() || value.isNegative()) {
			throw new IllegalStateException(property + " must be positive");
		}
		return value;
	}
}
