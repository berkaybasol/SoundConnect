package com.berkayb.soundconnect.modules.media.deletion;

import com.berkayb.soundconnect.modules.media.image.MediaImageVariantProperties;
import com.berkayb.soundconnect.modules.media.transcode.config.TranscodeProperties;
import com.berkayb.soundconnect.modules.media.transcode.config.TranscodeVariant;
import com.berkayb.soundconnect.modules.media.transcode.config.MediaTranscodeLeaseProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MediaDeletionProducerSafetyValidatorTest {

	@Test
	void defaultsCoverBoundedImageVideoAndStaleRecoveryWindows() {
		MediaDeletionProducerSafetyValidator validator = validator(new MediaDeletionProperties());

		assertThatNoException().isThrownBy(validator::validate);
	}

	@Test
	void unsafeOperationalOverrideFailsStartupContract() {
		MediaDeletionProperties deletion = new MediaDeletionProperties();
		deletion.setPublicVideoProducerGrace(Duration.ofHours(2));

		assertThatThrownBy(() -> validator(deletion).validate())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("public-video-producer-grace");
	}

	@Test
	void staleHlsCleanupCannotOvertakeConfiguredProducerUpperBound() {
		TranscodeProperties transcode = new TranscodeProperties();
		transcode.setLadder(List.of(
				new TranscodeVariant(), new TranscodeVariant(),
				new TranscodeVariant(), new TranscodeVariant()));
		transcode.setProcessTimeoutSec(21_600);
		transcode.setHlsUploadTimeoutSec(7_200);
		MediaDeletionProperties deletion = new MediaDeletionProperties();
		deletion.setPublicVideoProducerGrace(Duration.ofDays(7));
		MediaDeletionProducerSafetyValidator validator =
				new MediaDeletionProducerSafetyValidator(
						deletion, new MediaImageVariantProperties(), transcode,
						new MediaTranscodeLeaseProperties(), 12, 1, 3_600_000);

		assertThatThrownBy(validator::validate)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("bounded HLS producer");
	}

	@Test
	void hardAttemptBoundaryIncludesNativeTerminationAndReaderJoinTail() {
		TranscodeProperties transcode = new TranscodeProperties();
		transcode.setLadder(List.of(
				new TranscodeVariant(), new TranscodeVariant(),
				new TranscodeVariant(), new TranscodeVariant()));
		transcode.setProcessTimeoutSec(3_600);
		transcode.setHlsUploadTimeoutSec(3_450);
		MediaDeletionProperties deletion = new MediaDeletionProperties();
		deletion.setPublicVideoProducerGrace(Duration.ofDays(1));
		var validator = new MediaDeletionProducerSafetyValidator(
				deletion, new MediaImageVariantProperties(), transcode,
				new MediaTranscodeLeaseProperties(), 6, 1, 60_000);

		assertThatThrownBy(validator::validate)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("bounded HLS producer");
	}

	private static MediaDeletionProducerSafetyValidator validator(
			MediaDeletionProperties deletionProperties
	) {
		TranscodeProperties transcode = new TranscodeProperties();
		transcode.setLadder(List.of(
				new TranscodeVariant(), new TranscodeVariant(),
				new TranscodeVariant(), new TranscodeVariant()));
		return new MediaDeletionProducerSafetyValidator(
				deletionProperties,
				new MediaImageVariantProperties(),
				transcode,
				new MediaTranscodeLeaseProperties(),
				12,
				1,
				3_600_000
		);
	}
}
