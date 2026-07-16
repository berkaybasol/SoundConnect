package com.berkayb.soundconnect.modules.media.abuse;

import com.berkayb.soundconnect.modules.media.enums.MediaStatus;
import com.berkayb.soundconnect.modules.media.repository.MediaAssetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MediaUploadCleanupStateService {

	private final MediaAssetRepository mediaAssetRepository;

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public boolean claim(UUID assetId, LocalDateTime cutoff) {
		return mediaAssetRepository.claimStaleUploadForCleanup(
				assetId,
				MediaStatus.UPLOADING,
				MediaStatus.CLEANUP_PENDING,
				cutoff
		) == 1;
	}

	@Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
	public Optional<CleanupTarget> getPendingTarget(UUID assetId) {
		return mediaAssetRepository.findById(assetId)
				.filter(asset -> asset.getStatus() == MediaStatus.CLEANUP_PENDING)
				.map(asset -> new CleanupTarget(
						asset.getId(), asset.getStorageKey(), asset.getCreatedAt(),
						asset.getUploadWriteAuthorityExpiresAt(),
						asset.getUploadVerificationAttemptToken(),
						asset.getUploadVerificationCleanupNotBefore()));
	}

	/**
	 * Finalizes only the exact cleanup intent whose storage objects were removed.
	 * The attempt token and immutable cleanup deadline are part of the CAS fence:
	 * a stale worker can therefore never clear a newer attempt's key or deadline.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public boolean finish(CleanupTarget target) {
		return mediaAssetRepository.findByIdForUpdate(target.assetId())
				.filter(asset -> matches(asset.getStatus(), asset.getStorageKey(),
						asset.getUploadVerificationAttemptToken(),
						asset.getUploadVerificationCleanupNotBefore(), target))
				.map(asset -> {
					asset.setStatus(MediaStatus.FAILED);
					asset.setStorageKey(null);
					asset.setSourceUrl(null);
					asset.setPlaybackUrl(null);
					asset.setThumbnailUrl(null);
					asset.setUploadVerificationAttemptToken(null);
					asset.setUploadVerificationLeaseExpiresAt(null);
					asset.setUploadVerificationAttemptDeadline(null);
					asset.setUploadVerificationCleanupNotBefore(null);
					mediaAssetRepository.save(asset);
					return true;
				})
				.orElse(false);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public boolean defer(CleanupTarget target) {
		return mediaAssetRepository.findByIdForUpdate(target.assetId())
				.filter(asset -> matches(asset.getStatus(), asset.getStorageKey(),
						asset.getUploadVerificationAttemptToken(),
						asset.getUploadVerificationCleanupNotBefore(), target))
				.map(asset -> mediaAssetRepository.deferUploadCleanup(
						target.assetId(), MediaStatus.CLEANUP_PENDING) == 1)
				.orElse(false);
	}

	private static boolean matches(
			MediaStatus status,
			String storageKey,
			UUID attemptToken,
			LocalDateTime cleanupNotBefore,
			CleanupTarget target
	) {
		return status == MediaStatus.CLEANUP_PENDING
				&& Objects.equals(storageKey, target.storageKey())
				&& Objects.equals(attemptToken, target.uploadVerificationAttemptToken())
				&& Objects.equals(cleanupNotBefore, target.uploadVerificationCleanupNotBefore());
	}

	public record CleanupTarget(
			UUID assetId,
			String storageKey,
			LocalDateTime createdAt,
			LocalDateTime uploadWriteAuthorityExpiresAt,
			UUID uploadVerificationAttemptToken,
			LocalDateTime uploadVerificationCleanupNotBefore
	) {
		public CleanupTarget(
				UUID assetId,
				String storageKey,
				LocalDateTime createdAt,
				LocalDateTime uploadWriteAuthorityExpiresAt
		) {
			this(assetId, storageKey, createdAt, uploadWriteAuthorityExpiresAt, null, null);
		}

		public CleanupTarget(
				UUID assetId,
				String storageKey,
				LocalDateTime createdAt,
				LocalDateTime uploadWriteAuthorityExpiresAt,
				UUID uploadVerificationAttemptToken
		) {
			this(assetId, storageKey, createdAt, uploadWriteAuthorityExpiresAt,
					uploadVerificationAttemptToken, null);
		}

		public CleanupTarget(UUID assetId, String storageKey, LocalDateTime createdAt) {
			this(assetId, storageKey, createdAt, null, null, null);
		}
	}
}
