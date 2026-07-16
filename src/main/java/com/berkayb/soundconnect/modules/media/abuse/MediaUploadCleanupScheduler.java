package com.berkayb.soundconnect.modules.media.abuse;

import com.berkayb.soundconnect.modules.media.enums.MediaStatus;
import com.berkayb.soundconnect.modules.media.repository.MediaAssetRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Component
@Slf4j
public class MediaUploadCleanupScheduler {

	private final MediaAssetRepository mediaAssetRepository;
	private final MediaUploadCleanupWorker cleanupWorker;
	private final MediaUploadCleanupProperties properties;
	private final MediaUploadCleanupDispatcher cleanupDispatcher;
	private final MediaProtectedUploadRecoveryRepository protectedUploadRecoveryRepository;
	private final TaskExecutor protectedUploadRecoveryExecutor;
	private int protectedRecoveryOffset;

	public MediaUploadCleanupScheduler(
			MediaAssetRepository mediaAssetRepository,
			MediaUploadCleanupWorker cleanupWorker,
			MediaUploadCleanupProperties properties,
			MediaUploadCleanupDispatcher cleanupDispatcher,
			MediaProtectedUploadRecoveryRepository protectedUploadRecoveryRepository,
			@Qualifier("mediaProtectedUploadRecoveryExecutor") TaskExecutor protectedUploadRecoveryExecutor
	) {
		this.mediaAssetRepository = mediaAssetRepository;
		this.cleanupWorker = cleanupWorker;
		this.properties = properties;
		this.cleanupDispatcher = cleanupDispatcher;
		this.protectedUploadRecoveryRepository = protectedUploadRecoveryRepository;
		this.protectedUploadRecoveryExecutor = protectedUploadRecoveryExecutor;
	}

	@Scheduled(
			initialDelayString = "${media.upload-cleanup.initial-delay-ms:600000}",
			fixedDelayString = "${media.upload-cleanup.fixed-delay-ms:900000}"
	)
	public void cleanup() {
		if (!properties.isEnabled()) return;

		PageRequest batch = PageRequest.of(0, properties.getBatchSize());
		LocalDateTime cutoff = LocalDateTime.now(ZoneOffset.UTC).minus(properties.getStaleAfter());
		var staleUploads = mediaAssetRepository.findByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
				MediaStatus.UPLOADING,
				cutoff,
				batch
		);
		staleUploads.forEach(asset -> cleanupWorker.claim(asset.getId(), cutoff));

		var pendingCleanup = mediaAssetRepository.findByStatusOrderByUpdatedAtAsc(
				MediaStatus.CLEANUP_PENDING,
				batch
		);
		for (var asset : pendingCleanup) {
			if (!cleanupDispatcher.submit(asset.getId())) break;
		}

		recoverProtectedMutableSources();

		if (!staleUploads.isEmpty() || !pendingCleanup.isEmpty()) {
			log.info("[media] stale upload cleanup pass staleCandidates={} cleanupCandidates={}",
					staleUploads.size(), pendingCleanup.size());
		}
	}

	/**
	 * Rotates over every READY private-verified row instead of repeatedly taking
	 * the first page. A failed delete remains recoverable because the immutable
	 * READY row is never changed and the cursor resets after each full sweep.
	 */
	private synchronized void recoverProtectedMutableSources() {
		if (!properties.isProtectedMutableRecoveryEnabled()) return;

		int batchSize = properties.getProtectedMutableRecoveryBatchSize();
		var candidates = protectedUploadRecoveryRepository.findReadyBatch(
				protectedRecoveryOffset, batchSize);
		if (candidates.isEmpty()) {
			protectedRecoveryOffset = 0;
			return;
		}

		int submitted = 0;
		for (var candidate : candidates) {
			try {
				protectedUploadRecoveryExecutor.execute(
						() -> cleanupWorker.cleanProtectedMutableSource(candidate));
				submitted++;
			} catch (TaskRejectedException saturated) {
				log.warn("[media] protected mutable recovery queue is saturated; cursor retained");
				break;
			}
		}

		protectedRecoveryOffset += submitted;
		if (submitted == candidates.size() && candidates.size() < batchSize) {
			protectedRecoveryOffset = 0;
		}
		if (submitted > 0) {
			log.info("[media] protected mutable recovery submitted={} nextOffset={}",
					submitted, protectedRecoveryOffset);
		}
	}
}
