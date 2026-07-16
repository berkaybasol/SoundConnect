package com.berkayb.soundconnect.modules.media.transcode;

import com.berkayb.soundconnect.modules.media.entity.MediaAsset;
import com.berkayb.soundconnect.modules.media.enums.MediaKind;
import com.berkayb.soundconnect.modules.media.enums.MediaStatus;
import com.berkayb.soundconnect.modules.media.enums.MediaVisibility;
import com.berkayb.soundconnect.modules.media.repository.MediaAssetRepository;
import com.berkayb.soundconnect.modules.media.storage.MediaPolicy;
import com.berkayb.soundconnect.modules.media.storage.StorageObjectKeys;
import com.berkayb.soundconnect.modules.media.transcode.config.MediaTranscodeLeaseProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Durable, at-least-once dispatcher for video transcode jobs.
 *
 * <p>The media row itself is the transactional dispatch intent. A video is
 * committed as {@link MediaStatus#TRANSCODE_QUEUED}; only then may a RabbitMQ
 * message be published. If the immediate after-commit attempt is interrupted,
 * the scheduled recovery scan republishes it. Duplicate messages are expected
 * and are made harmless by the consumer's atomic state claim.</p>
 */
@Component
@Slf4j
@ConditionalOnProperty(
		prefix = "media.transcode.dispatch",
		name = "enabled",
		havingValue = "true",
		matchIfMissing = true
)
public class MediaTranscodeDispatcher {
	private final MediaAssetRepository mediaAssetRepository;
	private final TranscodePublisher transcodePublisher;
	private final MediaPolicy mediaPolicy;
	private final MediaAssetStatusUpdater mediaAssetStatusUpdater;
	private final MediaHlsCleanupDispatcher hlsCleanupDispatcher;
	private final MediaTranscodeLeaseProperties leaseProperties;
	private final TaskExecutor dispatchExecutor;
	private final Set<UUID> dispatchInFlight = ConcurrentHashMap.newKeySet();

	public MediaTranscodeDispatcher(
			MediaAssetRepository mediaAssetRepository,
			TranscodePublisher transcodePublisher,
			MediaPolicy mediaPolicy,
			MediaAssetStatusUpdater mediaAssetStatusUpdater,
			MediaHlsCleanupDispatcher hlsCleanupDispatcher,
			MediaTranscodeLeaseProperties leaseProperties,
			@Qualifier("mediaTranscodeDispatchExecutor") TaskExecutor dispatchExecutor
	) {
		this.mediaAssetRepository = mediaAssetRepository;
		this.transcodePublisher = transcodePublisher;
		this.mediaPolicy = mediaPolicy;
		this.mediaAssetStatusUpdater = mediaAssetStatusUpdater;
		this.hlsCleanupDispatcher = hlsCleanupDispatcher;
		this.leaseProperties = leaseProperties;
		this.dispatchExecutor = dispatchExecutor;
	}

	@Value("${media.transcode.dispatch.batch-size:50}")
	private int batchSize;

	@Value("${media.transcode.sent-recovery-delay:PT5M}")
	private Duration sentRecoveryDelay;

	@Value("${media.transcode.hls-cleanup-quiet-period-hours:1}")
	private long hlsCleanupQuietPeriodHours;

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onQueued(MediaTranscodeQueuedEvent event) {
		if (event != null && event.assetId() != null) {
			submit(event.assetId());
		}
	}

	@Scheduled(
			initialDelayString = "${media.transcode.dispatch.initial-delay-ms:15000}",
			fixedDelayString = "${media.transcode.dispatch.fixed-delay-ms:30000}"
	)
	public void recoverQueuedJobs() {
		int safeBatchSize = Math.max(1, Math.min(batchSize, 500));
		mediaAssetRepository.findByKindAndVisibilityAndStatusOrderByCreatedAtAsc(
				MediaKind.VIDEO,
				MediaVisibility.PUBLIC,
				MediaStatus.TRANSCODE_QUEUED,
				PageRequest.of(0, safeBatchSize)
		).forEach(asset -> submit(asset.getId()));
	}

	@Scheduled(
			initialDelayString = "${media.transcode.sent-recovery-initial-delay-ms:60000}",
			fixedDelayString = "${media.transcode.sent-recovery-check-ms:60000}"
	)
	@Transactional
	public void recoverStaleSentJobs() {
		int safeBatchSize = Math.max(1, Math.min(batchSize, 500));
		Duration safeRecoveryDelay = sentRecoveryDelay == null
				? Duration.ofMinutes(5)
				: sentRecoveryDelay;
		if (safeRecoveryDelay.compareTo(Duration.ofMinutes(1)) < 0) {
			safeRecoveryDelay = Duration.ofMinutes(1);
		}
		LocalDateTime cutoff = LocalDateTime.now(ZoneOffset.UTC).minus(safeRecoveryDelay);
		var staleJobs = mediaAssetRepository
				.findByKindAndVisibilityAndStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(
						MediaKind.VIDEO,
						MediaVisibility.PUBLIC,
						MediaStatus.TRANSCODE_SENT,
						cutoff,
						PageRequest.of(0, safeBatchSize)
				);
		int requeued = 0;
		for (MediaAsset asset : staleJobs) {
			requeued += mediaAssetRepository.requeueStaleSentTranscode(
					asset.getId(),
					MediaKind.VIDEO,
					MediaVisibility.PUBLIC,
					MediaStatus.TRANSCODE_SENT,
					MediaStatus.TRANSCODE_QUEUED,
					cutoff
			);
		}
		if (requeued > 0) {
			log.warn("[media-transcode] requeued {} stale broker-confirmed job(s); cutoff={}",
					requeued, cutoff);
		}
	}

	@Scheduled(
			initialDelayString = "${media.transcode.stale-job-initial-delay-ms:60000}",
			fixedDelayString = "${media.transcode.stale-job-check-ms:3600000}"
	)
	@Transactional
	public void recoverExpiredProcessingJobs() {
		LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
		int retryCleanup = mediaAssetRepository.recoverExpiredTranscodesForRetry(
				MediaKind.VIDEO,
				MediaVisibility.PUBLIC,
				MediaStatus.PROCESSING,
				MediaStatus.HLS_CLEANUP,
				now,
				leaseProperties.getMaxAttempts()
		);
		int terminalCleanup = mediaAssetRepository.recoverExhaustedTranscodesForCleanup(
				MediaKind.VIDEO,
				MediaVisibility.PUBLIC,
				MediaStatus.PROCESSING,
				MediaStatus.HLS_CLEANUP,
				now,
				leaseProperties.getMaxAttempts()
		);
		if (retryCleanup + terminalCleanup > 0) {
			log.warn("[media-transcode] expired lease recovery retryCleanup={} terminalCleanup={} now={}",
					retryCleanup, terminalCleanup, now);
		}
	}

	@Scheduled(
			initialDelayString = "${media.transcode.hls-cleanup-initial-delay-ms:90000}",
			fixedDelayString = "${media.transcode.hls-cleanup-check-ms:60000}"
	)
	public void recoverHlsCleanupJobs() {
		int safeBatchSize = Math.max(1, Math.min(batchSize, 500));
		long safeQuietPeriodHours = Math.max(1, hlsCleanupQuietPeriodHours);
		LocalDateTime cutoff = LocalDateTime.now(ZoneOffset.UTC).minusHours(safeQuietPeriodHours);
		var pending = mediaAssetRepository.findByKindAndVisibilityAndStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(
				MediaKind.VIDEO,
				MediaVisibility.PUBLIC,
				MediaStatus.HLS_CLEANUP,
				cutoff,
				PageRequest.of(0, safeBatchSize)
		);
		for (MediaAsset asset : pending) {
			if (!hlsCleanupDispatcher.submit(asset.getId())) break;
		}
	}

	/** @return false only when the bounded executor cannot accept more work. */
	boolean submit(UUID assetId) {
		if (assetId == null) return true;
		if (!dispatchInFlight.add(assetId)) return true;
		try {
			dispatchExecutor.execute(() -> {
				try {
					dispatchOne(assetId);
				} finally {
					dispatchInFlight.remove(assetId);
				}
			});
			return true;
		} catch (TaskRejectedException saturated) {
			dispatchInFlight.remove(assetId);
			log.warn("[media-transcode] dispatch queue saturated; QUEUED intent retained");
			return false;
		} catch (RuntimeException submissionFailure) {
			dispatchInFlight.remove(assetId);
			throw submissionFailure;
		}
	}

	private void dispatchOne(UUID assetId) {
		MediaAsset asset = mediaAssetRepository.findById(assetId).orElse(null);
		if (asset == null
				|| asset.getKind() != MediaKind.VIDEO
				|| asset.getVisibility() != MediaVisibility.PUBLIC
				|| asset.getStatus() != MediaStatus.TRANSCODE_QUEUED
				|| !StringUtils.hasText(asset.getStorageKey())
				|| !StorageObjectKeys.isVerified(asset.getStorageKey())
				|| StorageObjectKeys.isProtected(asset.getStorageKey())) {
			return;
		}

		String hlsPrefix = mediaPolicy.buildHlsPrefix(assetId);
		boolean markedSent = mediaAssetStatusUpdater.markTranscodeSent(assetId);
		if (!markedSent) return;
		try {
			transcodePublisher.publishVideoHls(assetId, asset.getStorageKey(), hlsPrefix);
			log.debug("[media-transcode] dispatched assetId={}", assetId);
		} catch (RuntimeException exception) {
			boolean requeued = mediaAssetStatusUpdater.requeueUnclaimedTranscodeSignal(assetId);
			log.warn("[media-transcode] publish failed assetId={} requeued={} exceptionType={}",
					assetId, requeued, exception.getClass().getSimpleName());
		}
	}
}
