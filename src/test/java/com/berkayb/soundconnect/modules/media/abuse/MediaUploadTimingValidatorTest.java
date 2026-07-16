package com.berkayb.soundconnect.modules.media.abuse;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MediaUploadTimingValidatorTest {

	@Test
	void acceptsTimersThatOutliveTheSignedPut() {
		MediaUploadGuardProperties guard = new MediaUploadGuardProperties();
		MediaUploadCleanupProperties cleanup = new MediaUploadCleanupProperties();

		assertThatCode(() -> new MediaUploadTimingValidator(guard, cleanup, 900).validate())
				.doesNotThrowAnyException();
	}

	@Test
	void rejectsReservationThatExpiresBeforeSignedPut() {
		MediaUploadGuardProperties guard = new MediaUploadGuardProperties();
		guard.setReservationTtl(Duration.ofMinutes(5));

		assertThatThrownBy(() -> new MediaUploadTimingValidator(
				guard,
				new MediaUploadCleanupProperties(),
				900
		).validate()).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("reservation-ttl");
	}

	@Test
	void rejectsCleanupThatCanRaceAStillValidSignedPut() {
		MediaUploadCleanupProperties cleanup = new MediaUploadCleanupProperties();
		cleanup.setStaleAfter(Duration.ofMinutes(10));

		assertThatThrownBy(() -> new MediaUploadTimingValidator(
				new MediaUploadGuardProperties(),
				cleanup,
				900
		).validate()).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("stale-after");
	}
}
