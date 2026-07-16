package com.berkayb.soundconnect.modules.media.recovery;

import com.berkayb.soundconnect.modules.media.storage.PresignedUploadWriteWindow;
import com.berkayb.soundconnect.modules.media.storage.StorageClient;
import com.berkayb.soundconnect.modules.media.storage.StorageObjectKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Idempotently removes deterministic private companions left by a failed
 * after-commit cleanup. The live public key is deliberately never deleted.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MediaPublicPromotionRecoveryWorker {

	private final StorageClient storageClient;
	private final PresignedUploadWriteWindow presignedUploadWriteWindow;
	private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

	public void recover(MediaPublicPromotionRecoveryTarget target) {
		if (!inFlight.add(target.assetId())) return;
		try {
			if (!isSafePublicKey(target)) return;

			String verifiedKey = StorageObjectKeys.verifiedKeyForPublicObject(target.publicKey());
			String quarantineKey = StorageObjectKeys.mutableUploadKeyForVerified(verifiedKey);
			RuntimeException cleanupFailure = delete(verifiedKey, null);

			if (isUploadWriteWindowClosed(target)) {
				cleanupFailure = delete(quarantineKey, cleanupFailure);
			} else {
				log.debug("[media] public quarantine cleanup waiting for PUT expiry assetId={}",
						target.assetId());
			}

			if (cleanupFailure != null) {
				log.warn("[media] public promotion companion cleanup will retry assetId={} exceptionType={}",
						target.assetId(), cleanupFailure.getClass().getSimpleName());
			}
		} catch (IllegalArgumentException unsafeKey) {
			log.error("[media] public promotion recovery rejected unsafe storage key assetId={}",
					target.assetId());
		} finally {
			inFlight.remove(target.assetId());
		}
	}

	private boolean isSafePublicKey(MediaPublicPromotionRecoveryTarget target) {
		if (!StringUtils.hasText(target.publicKey())
				|| StorageObjectKeys.isPrivateOrigin(target.publicKey())) {
			log.error("[media] public promotion recovery skipped non-public storage key assetId={}",
					target.assetId());
			return false;
		}
		return true;
	}

	private RuntimeException delete(String objectKey, RuntimeException previousFailure) {
		try {
			storageClient.deleteObject(objectKey);
			return previousFailure;
		} catch (RuntimeException currentFailure) {
			if (previousFailure == null) return currentFailure;
			previousFailure.addSuppressed(currentFailure);
			return previousFailure;
		}
	}

	private boolean isUploadWriteWindowClosed(MediaPublicPromotionRecoveryTarget target) {
		if (target.uploadWriteAuthorityExpiresAt() == null) {
			return presignedUploadWriteWindow.isSafeToDelete(target.createdAt());
		}
		return presignedUploadWriteWindow.isSafeToDelete(
				target.uploadWriteAuthorityExpiresAt(), target.createdAt());
	}
}
