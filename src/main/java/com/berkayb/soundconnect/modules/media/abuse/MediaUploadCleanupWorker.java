package com.berkayb.soundconnect.modules.media.abuse;

import com.berkayb.soundconnect.modules.media.storage.StorageClient;
import com.berkayb.soundconnect.modules.media.storage.PresignedUploadWriteWindow;
import com.berkayb.soundconnect.modules.media.storage.StorageObjectKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class MediaUploadCleanupWorker {

	private final MediaUploadCleanupStateService stateService;
	private final MediaUploadAbuseGuard abuseGuard;
	private final StorageClient storageClient;
	private final PresignedUploadWriteWindow presignedUploadWriteWindow;
	private final Set<UUID> protectedRecoveryInFlight = ConcurrentHashMap.newKeySet();

	public void claim(UUID assetId, LocalDateTime cutoff) {
		if (stateService.claim(assetId, cutoff)) {
			abuseGuard.release(assetId);
		}
	}

	public void clean(UUID assetId) {
		// This runs only on the bounded cleanup executor. Releasing here guarantees
		// both the immediate and scheduled recovery paths eventually free the slot.
		abuseGuard.release(assetId);
		stateService.getPendingTarget(assetId).ifPresent(target -> {
			if (!isVerificationCleanupFenceElapsed(
					target.uploadVerificationCleanupNotBefore())) {
				// A timed-out SDK call may still complete remotely. Keep the exact
				// token/key cleanup intent durable until every producer tail is over.
				stateService.defer(target);
				log.info("[media] upload cleanup waiting for verification write fence assetId={} notBefore={}",
						target.assetId(), target.uploadVerificationCleanupNotBefore());
				return;
			}
			if (StorageObjectKeys.isClientWritableUpload(target.storageKey())
					&& !isUploadWriteWindowClosed(
						target.uploadWriteAuthorityExpiresAt(), target.createdAt())) {
				// The object may not exist yet: a signed URL can still PUT it. A later
				// scheduled pass performs the final delete after write authority expires.
				stateService.defer(target);
				log.info("[media] upload cleanup waiting for presigned PUT expiry assetId={}",
						target.assetId());
				return;
			}

			RuntimeException cleanupFailure = null;
			if (StringUtils.hasText(target.storageKey())) {
				for (String prefix : StorageObjectKeys.attemptPrefixesFor(target.storageKey())) {
					try {
						// No committed winner exists in CLEANUP_PENDING. Sweep all
						// reclaimed A/B attempt subtrees before retiring the row.
						storageClient.deleteFolder(prefix);
					} catch (RuntimeException exception) {
						cleanupFailure = combine(cleanupFailure, exception);
					}
				}
				for (String key : StorageObjectKeys.abandonedUploadCleanupKeys(
						target.storageKey(), target.uploadVerificationAttemptToken())) {
					try {
						storageClient.deleteObject(key);
					} catch (RuntimeException exception) {
						cleanupFailure = combine(cleanupFailure, exception);
					}
				}
			}
			if (cleanupFailure == null) {
				stateService.finish(target);
			} else {
				// CLEANUP_PENDING is deliberately retained. The next scheduled pass
				// retries an idempotent object delete. Move this row behind older
				// untouched work so one unavailable object cannot starve the batch.
				try {
					stateService.defer(target);
				} catch (RuntimeException stateFailure) {
					cleanupFailure.addSuppressed(stateFailure);
					log.warn("[media] stale upload cleanup retry scheduling failed assetId={} exceptionType={}",
							target.assetId(), stateFailure.getClass().getSimpleName());
				}
				log.warn("[media] stale upload object cleanup will retry assetId={} exceptionType={}",
						target.assetId(), cleanupFailure.getClass().getSimpleName());
			}
		});
	}

	/**
	 * Removes only the client-writable predecessor of a committed protected
	 * snapshot. The READY row remains the durable recovery intent; failures are
	 * therefore retried on a later complete scheduler sweep without blocking
	 * unrelated assets.
	 */
	public void cleanProtectedMutableSource(
			MediaProtectedUploadRecoveryRepository.RecoveryTarget target
	) {
		if (!protectedRecoveryInFlight.add(target.assetId())) return;
		try {
			if (!isUploadWriteWindowClosed(
					target.uploadWriteAuthorityExpiresAt(), target.createdAt())) {
				log.debug("[media] protected mutable cleanup waiting for PUT expiry assetId={}",
						target.assetId());
				return;
			}

			String mutableKey = StorageObjectKeys.mutableUploadKeyForPrivateVerified(
					target.privateVerifiedKey());
			storageClient.deleteObject(mutableKey);
			log.debug("[media] protected mutable upload cleanup completed assetId={}",
					target.assetId());
		} catch (RuntimeException cleanupFailure) {
			// READY remains unchanged, so this exact deterministic key is recovered
			// again after the cursor completes its sweep.
			log.warn("[media] protected mutable upload cleanup will retry assetId={} exceptionType={}",
					target.assetId(), cleanupFailure.getClass().getSimpleName());
		} finally {
			protectedRecoveryInFlight.remove(target.assetId());
		}
	}

	private static RuntimeException combine(RuntimeException previous, RuntimeException next) {
		if (previous == null) return next;
		previous.addSuppressed(next);
		return previous;
	}

	private boolean isUploadWriteWindowClosed(
			LocalDateTime authorityExpiresAt,
			LocalDateTime createdAt
	) {
		if (authorityExpiresAt == null) {
			return presignedUploadWriteWindow.isSafeToDelete(createdAt);
		}
		return presignedUploadWriteWindow.isSafeToDelete(authorityExpiresAt, createdAt);
	}

	private static boolean isVerificationCleanupFenceElapsed(LocalDateTime cleanupNotBefore) {
		return cleanupNotBefore == null
				|| !LocalDateTime.now(ZoneOffset.UTC).isBefore(cleanupNotBefore);
	}
}
