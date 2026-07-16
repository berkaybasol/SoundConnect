package com.berkayb.soundconnect.modules.media.transcode;

import com.berkayb.soundconnect.modules.media.storage.MediaPolicy;
import com.berkayb.soundconnect.modules.media.storage.PresignedUploadWriteWindow;
import com.berkayb.soundconnect.modules.media.storage.StorageClient;
import com.berkayb.soundconnect.modules.media.storage.StorageObjectKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** Runs retryable HLS cleanup outside Spring's shared scheduler thread. */
@Component
@Slf4j
public class MediaHlsCleanupDispatcher {

	private final StorageClient storageClient;
	private final MediaPolicy mediaPolicy;
	private final MediaAssetStatusUpdater statusUpdater;
	private final PresignedUploadWriteWindow presignedUploadWriteWindow;
	private final TaskExecutor executor;
	private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

	public MediaHlsCleanupDispatcher(
			StorageClient storageClient,
			MediaPolicy mediaPolicy,
			MediaAssetStatusUpdater statusUpdater,
			PresignedUploadWriteWindow presignedUploadWriteWindow,
			@Qualifier("mediaHlsCleanupExecutor") TaskExecutor executor
	) {
		this.storageClient = storageClient;
		this.mediaPolicy = mediaPolicy;
		this.statusUpdater = statusUpdater;
		this.presignedUploadWriteWindow = presignedUploadWriteWindow;
		this.executor = executor;
	}

	/** @return false only when the bounded executor is saturated. */
	public boolean submit(UUID assetId) {
		if (assetId == null) return true;
		if (!inFlight.add(assetId)) return true;
		try {
			executor.execute(() -> clean(assetId));
			return true;
		} catch (TaskRejectedException saturated) {
			inFlight.remove(assetId);
			log.warn("[media-transcode] HLS cleanup queue saturated; remaining work deferred");
			return false;
		} catch (RuntimeException submissionFailure) {
			inFlight.remove(assetId);
			throw submissionFailure;
		}
	}

	private void clean(UUID assetId) {
		try {
			var cleanupTarget = statusUpdater.getHlsCleanupTarget(assetId);
			if (cleanupTarget.isEmpty()) {
				log.info("[media-transcode] HLS cleanup no longer owned assetId={}", assetId);
				return;
			}
			var target = cleanupTarget.orElseThrow();
			if (target.cleanupNotBefore() != null
					&& LocalDateTime.now(ZoneOffset.UTC).isBefore(target.cleanupNotBefore())) {
				statusUpdater.deferHlsCleanup(assetId);
				log.info("[media-transcode] HLS cleanup retained through hard attempt deadline assetId={} notBefore={}",
						assetId, target.cleanupNotBefore());
				return;
			}

			if (target.retryAfterCleanup()) {
				// Crash recovery preserves the immutable, verified source. Only the
				// deterministic derivative tree may contain partial bytes from the dead
				// attempt, and requeue happens strictly after this delete succeeds.
				storageClient.deleteFolder(mediaPolicy.buildHlsPrefix(assetId));
				storageClient.invalidatePublicAsset(assetId);
				var outcome = statusUpdater.completeHlsRetryCleanup(assetId);
				log.info("[media-transcode] HLS retry cleanup completed assetId={} outcome={}",
						assetId, outcome);
				return;
			}

			if (target.retainSourceAfterCleanup()) {
				storageClient.deleteFolder(mediaPolicy.buildHlsPrefix(assetId));
				storageClient.invalidatePublicAsset(assetId);
				boolean completed = statusUpdater.completeRetainedSourceFailure(assetId);
				log.warn("[media-transcode] exhausted retry derivatives cleaned; verified source retained privately for manual recovery assetId={} stateUpdated={}",
						assetId, completed);
				return;
			}

			String sourceKey = target.sourceKey();
			if (StringUtils.hasText(sourceKey)) {
				for (String prefix : StorageObjectKeys.attemptPrefixesFor(sourceKey)) {
					storageClient.deleteFolder(prefix);
				}
				for (String key : StorageObjectKeys.abandonedUploadCleanupKeys(sourceKey)) {
					storageClient.deleteObject(key);
				}
			}
			storageClient.deleteFolder(mediaPolicy.buildHlsPrefix(assetId));
			storageClient.invalidatePublicAsset(assetId);
			if (!isUploadWriteWindowClosed(target)) {
				statusUpdater.deferHlsCleanup(assetId);
				log.info("[media-transcode] HLS cleanup retained through PUT completion grace assetId={}",
						assetId);
				return;
			}
			boolean completed = statusUpdater.completeHlsCleanup(assetId);
			log.info("[media-transcode] HLS cleanup completed assetId={} stateUpdated={}",
					assetId, completed);
		} catch (RuntimeException cleanupFailure) {
			try {
				statusUpdater.deferHlsCleanup(assetId);
			} catch (RuntimeException stateFailure) {
				cleanupFailure.addSuppressed(stateFailure);
			}
			log.warn("[media-transcode] HLS cleanup deferred assetId={} exceptionType={}",
					assetId, cleanupFailure.getClass().getSimpleName());
		} finally {
			inFlight.remove(assetId);
		}
	}

	private boolean isUploadWriteWindowClosed(MediaAssetStatusUpdater.HlsCleanupTarget target) {
		if (target.uploadWriteAuthorityExpiresAt() == null) {
			return presignedUploadWriteWindow.isSafeToDelete(target.createdAt());
		}
		return presignedUploadWriteWindow.isSafeToDelete(
				target.uploadWriteAuthorityExpiresAt(), target.createdAt());
	}
}
