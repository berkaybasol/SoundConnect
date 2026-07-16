package com.berkayb.soundconnect.modules.media.abuse;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Ensures cleanup/reservation timers can never expire a still-valid signed PUT. */
@Component
public class MediaUploadTimingValidator {

	private final MediaUploadGuardProperties guardProperties;
	private final MediaUploadCleanupProperties cleanupProperties;
	private final Duration uploadPresignTtl;

	public MediaUploadTimingValidator(
			MediaUploadGuardProperties guardProperties,
			MediaUploadCleanupProperties cleanupProperties,
			@Value("${cloud.storage.presign.uploadExpirySeconds:${cloud.storage.presign.expirySeconds:900}}")
			int uploadPresignExpirySeconds
	) {
		this.guardProperties = guardProperties;
		this.cleanupProperties = cleanupProperties;
		this.uploadPresignTtl = Duration.ofSeconds(uploadPresignExpirySeconds);
	}

	@PostConstruct
	void validate() {
		if (guardProperties.isEnabled()
				&& guardProperties.getReservationTtl().compareTo(uploadPresignTtl) < 0) {
			throw new IllegalStateException(
					"media.upload-guard.reservation-ttl must be at least the upload presign expiry"
			);
		}
		if (cleanupProperties.isEnabled()
				&& cleanupProperties.getStaleAfter().compareTo(uploadPresignTtl) <= 0) {
			throw new IllegalStateException(
					"media.upload-cleanup.stale-after must be greater than the upload presign expiry"
			);
		}
		if (guardProperties.isEnabled()
				&& cleanupProperties.isEnabled()
				&& cleanupProperties.getStaleAfter().compareTo(guardProperties.getReservationTtl()) < 0) {
			throw new IllegalStateException(
					"media.upload-cleanup.stale-after must be at least the reservation TTL"
			);
		}
	}
}
