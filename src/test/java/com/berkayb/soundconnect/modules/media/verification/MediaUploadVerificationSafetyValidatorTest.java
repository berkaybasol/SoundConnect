package com.berkayb.soundconnect.modules.media.verification;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MediaUploadVerificationSafetyValidatorTest {

	@Test
	void defaultsCoverBoundedStoragePathAndRecoveryCadence() {
		MediaUploadVerificationProperties properties = new MediaUploadVerificationProperties();

		assertThatCode(() -> new MediaUploadVerificationSafetyValidator(properties).validate())
				.doesNotThrowAnyException();
	}

	@Test
	void rejectsLeaseThatCanExpireWhileWorkerStillOwnsStorageCalls() {
		MediaUploadVerificationProperties properties = new MediaUploadVerificationProperties();
		properties.setVerificationLease(Duration.ofMinutes(2));

		assertThatThrownBy(() -> new MediaUploadVerificationSafetyValidator(properties).validate())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("verification-lease must be at least PT3M30S");
	}

	@Test
	void rejectsServletWaitOutsideBoundedFastPathWindow() {
		MediaUploadVerificationProperties properties = new MediaUploadVerificationProperties();
		properties.setRequestWaitTimeout(Duration.ofSeconds(30));

		assertThatThrownBy(() -> new MediaUploadVerificationSafetyValidator(properties).validate())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("between PT3S and PT5S");
	}

	@Test
	void rejectsHardDeadlineThatDoesNotFenceLeaseAndFinalStorageTail() {
		MediaUploadVerificationProperties properties = new MediaUploadVerificationProperties();
		properties.setAttemptHardTimeout(Duration.ofMinutes(4));

		assertThatThrownBy(() -> new MediaUploadVerificationSafetyValidator(properties).validate())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("attempt-hard-timeout must be at least PT5M");
	}
}
