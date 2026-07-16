package com.berkayb.soundconnect.modules.media.service;

import com.berkayb.soundconnect.modules.media.abuse.MediaUploadCleanupDispatcher;
import com.berkayb.soundconnect.modules.media.enums.MediaStatus;
import com.berkayb.soundconnect.modules.media.repository.MediaAssetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Slf4j
public class MediaUploadFailureHandler {

	private final MediaAssetRepository mediaAssetRepository;
	private final MediaUploadCleanupDispatcher mediaUploadCleanupDispatcher;

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void recordRejectedUpload(UUID assetId, String storageKey) {
		recordRejectedUploadInternal(assetId, storageKey, null);
	}

	/**
	 * Rejects only the exact durable verification attempt that inspected the
	 * object. A late worker must never convert a newer cross-node attempt into a
	 * cleanup intent.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void recordRejectedUpload(UUID assetId, String storageKey, UUID verificationAttemptToken) {
		recordRejectedUploadInternal(assetId, storageKey, verificationAttemptToken);
	}

	private void recordRejectedUploadInternal(
			UUID assetId,
			String storageKey,
			UUID verificationAttemptToken
	) {
		boolean claimed = mediaAssetRepository.findByIdForUpdate(assetId)
				.filter(asset -> verificationAttemptToken == null
						? asset.getStatus() == MediaStatus.UPLOADING
						: asset.getStatus() == MediaStatus.VERIFYING
								&& Objects.equals(
										asset.getUploadVerificationAttemptToken(),
										verificationAttemptToken))
				.filter(asset -> Objects.equals(asset.getStorageKey(), storageKey))
				.map(asset -> {
					asset.setStatus(MediaStatus.CLEANUP_PENDING);
					asset.setUploadVerificationLeaseExpiresAt(null);
					mediaAssetRepository.save(asset);
					return true;
				})
				.orElse(false);
		if (!claimed) {
			// In particular, never delete the retained quarantine source of a
			// TRANSCODE_QUEUED/PROCESSING/FAILED video.
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				// Submission is bounded and non-blocking. Saturation/process death is
				// harmless because CLEANUP_PENDING is the scheduled recovery intent.
				try {
					mediaUploadCleanupDispatcher.submit(assetId);
				} catch (RuntimeException submissionFailure) {
					log.warn("[media] rejected upload cleanup submission deferred assetId={} exceptionType={}",
							assetId, submissionFailure.getClass().getSimpleName());
				}
			}
		});
	}
}
