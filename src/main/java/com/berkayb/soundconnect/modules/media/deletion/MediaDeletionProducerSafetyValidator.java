package com.berkayb.soundconnect.modules.media.deletion;

import com.berkayb.soundconnect.modules.media.image.MediaImageVariantProperties;
import com.berkayb.soundconnect.modules.media.transcode.config.TranscodeProperties;
import com.berkayb.soundconnect.modules.media.transcode.config.MediaTranscodeLeaseProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Fails startup if physical-deletion grace can end while a bounded producer is
 * still able to create a public object. Keeping this contract executable makes
 * operational timeout overrides safe instead of relying on matching comments.
 */
@Component
public class MediaDeletionProducerSafetyValidator {

	private static final long STORAGE_CALL_TIMEOUT_SECONDS = 30;
	private static final long SCHEDULING_AND_DB_MARGIN_SECONDS = 60;
	/** Covers per-child terminate/wait tails and bounded stdout/stderr joins. */
	private static final long VIDEO_NATIVE_SHUTDOWN_TAIL_SECONDS = 300;

	private final MediaDeletionProperties deletionProperties;
	private final MediaImageVariantProperties imageProperties;
	private final TranscodeProperties transcodeProperties;
	private final MediaTranscodeLeaseProperties leaseProperties;
	private final long processingTimeoutHours;
	private final long hlsCleanupQuietPeriodHours;
	private final long staleJobCheckMs;

	public MediaDeletionProducerSafetyValidator(
			MediaDeletionProperties deletionProperties,
			MediaImageVariantProperties imageProperties,
			TranscodeProperties transcodeProperties,
			MediaTranscodeLeaseProperties leaseProperties,
			@Value("${media.transcode.processing-timeout-hours:12}") long processingTimeoutHours,
			@Value("${media.transcode.hls-cleanup-quiet-period-hours:1}") long hlsCleanupQuietPeriodHours,
			@Value("${media.transcode.stale-job-check-ms:3600000}") long staleJobCheckMs
	) {
		this.deletionProperties = deletionProperties;
		this.imageProperties = imageProperties;
		this.transcodeProperties = transcodeProperties;
		this.leaseProperties = leaseProperties;
		this.processingTimeoutHours = processingTimeoutHours;
		this.hlsCleanupQuietPeriodHours = hlsCleanupQuietPeriodHours;
		this.staleJobCheckMs = staleJobCheckMs;
	}

	@PostConstruct
	public void validate() {
		Duration requiredImageGrace = Duration.ofSeconds(
				2L * STORAGE_CALL_TIMEOUT_SECONDS
						+ 2L * transcodeProperties.getFfprobeTimeoutSec()
						+ imageProperties.getProcessTimeoutSeconds()
						+ SCHEDULING_AND_DB_MARGIN_SECONDS
		);

		long nativeProcessBudget = Math.multiplyExact(
				(long) transcodeProperties.getLadder().size() + 1L,
				(long) transcodeProperties.getProcessTimeoutSec()
		);
		Duration boundedVideoProducer = Duration.ofSeconds(
				transcodeProperties.getSourceDownloadTimeoutSec()
						+ transcodeProperties.getFfprobeTimeoutSec()
						+ nativeProcessBudget
						+ transcodeProperties.getHlsUploadTimeoutSec()
						+ STORAGE_CALL_TIMEOUT_SECONDS
						+ VIDEO_NATIVE_SHUTDOWN_TAIL_SECONDS
						+ SCHEDULING_AND_DB_MARGIN_SECONDS
		);
		Duration hardAttemptWindow = Duration.ofHours(Math.max(1L, processingTimeoutHours));
		if (hardAttemptWindow.compareTo(boundedVideoProducer) < 0) {
			throw new IllegalStateException(
					"media.transcode.processing-timeout-hours must cover the bounded HLS producer: "
							+ boundedVideoProducer);
		}
		Duration cleanupQuietPeriod = Duration.ofHours(Math.max(1L, hlsCleanupQuietPeriodHours));
		Duration boundedLateUpload = Duration.ofSeconds(transcodeProperties.getHlsUploadTimeoutSec());
		if (cleanupQuietPeriod.compareTo(boundedLateUpload) < 0) {
			throw new IllegalStateException(
					"media.transcode HLS cleanup quiet period must cover the upload timeout: "
							+ boundedLateUpload);
		}
		if (!leaseProperties.isHeartbeatWindowSafe()) {
			throw new IllegalStateException("media.transcode.lease heartbeat window is unsafe");
		}
		Duration recoveryDetectionMargin = Duration.ofMillis(Math.max(1_000L, staleJobCheckMs));
		Duration durableAttemptTail = hardAttemptWindow
				.plus(recoveryDetectionMargin)
				.plusMinutes(1);
		Duration requiredVideoGrace = boundedVideoProducer.compareTo(durableAttemptTail) >= 0
				? boundedVideoProducer
				: durableAttemptTail;

		if (deletionProperties.getPublicImageProducerGrace().compareTo(requiredImageGrace) < 0) {
			throw new IllegalStateException(
					"media.deletion.public-image-producer-grace must be at least "
							+ requiredImageGrace);
		}
		if (deletionProperties.getPublicVideoProducerGrace().compareTo(requiredVideoGrace) < 0) {
			throw new IllegalStateException(
					"media.deletion.public-video-producer-grace must be at least "
							+ requiredVideoGrace);
		}
	}
}
