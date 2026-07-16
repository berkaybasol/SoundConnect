package com.berkayb.soundconnect.modules.media.verification;

import com.berkayb.soundconnect.modules.media.storage.StorageClient;
import com.berkayb.soundconnect.modules.media.storage.StorageObjectKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Sweeps expired A/B attempt objects while preserving the exact committed key. */
@Component
@Slf4j
public class MediaUploadVerificationOrphanSweeper {

	private final MediaUploadVerificationOrphanRepository repository;
	private final StorageClient storageClient;
	private final MediaUploadVerificationProperties properties;
	private final TaskExecutor executor;
	private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

	public MediaUploadVerificationOrphanSweeper(
			MediaUploadVerificationOrphanRepository repository,
			StorageClient storageClient,
			MediaUploadVerificationProperties properties,
			@Qualifier("mediaUploadVerificationExecutor") TaskExecutor executor
	) {
		this.repository = repository;
		this.storageClient = storageClient;
		this.properties = properties;
		this.executor = executor;
	}

	@Scheduled(
			initialDelayString = "${media.upload-verification.recovery-initial-delay-ms:15000}",
			fixedDelayString = "${media.upload-verification.recovery-fixed-delay-ms:30000}"
	)
	public void sweepEligible() {
		if (!properties.isEnabled()) return;
		int batch = Math.max(1, Math.min(properties.getRecoveryBatchSize(), 500));
		for (MediaUploadVerificationOrphanTarget target : repository.findEligibleBatch(
				LocalDateTime.now(ZoneOffset.UTC), batch)) {
			if (!inFlight.add(target.assetId())) continue;
			try {
				executor.execute(() -> sweep(target));
			} catch (TaskRejectedException saturated) {
				inFlight.remove(target.assetId());
				log.warn("[media] verification orphan sweep queue saturated; work deferred");
				break;
			} catch (RuntimeException submissionFailure) {
				inFlight.remove(target.assetId());
				throw submissionFailure;
			}
		}
	}

	void sweep(MediaUploadVerificationOrphanTarget target) {
		try {
			for (String prefix : StorageObjectKeys.attemptPrefixesFor(target.currentStorageKey())) {
				String retainedSubtree = StorageObjectKeys.retainedAttemptSubtreeForPrefix(
						target.currentStorageKey(), prefix);
				storageClient.deleteFolderExceptPrefix(prefix, retainedSubtree);
			}
			repository.markSwept(target);
		} catch (RuntimeException cleanupFailure) {
			log.warn("[media] verification orphan sweep will retry assetId={} exceptionType={}",
					target.assetId(), cleanupFailure.getClass().getSimpleName());
		} finally {
			inFlight.remove(target.assetId());
		}
	}
}
