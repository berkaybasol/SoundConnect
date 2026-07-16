package com.berkayb.soundconnect.modules.media.deletion;

import com.berkayb.soundconnect.modules.media.enums.MediaKind;
import com.berkayb.soundconnect.modules.media.enums.MediaStatus;
import com.berkayb.soundconnect.modules.media.enums.MediaVisibility;
import com.berkayb.soundconnect.modules.media.repository.MediaAssetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MediaDeletionStateService {

	private final MediaAssetRepository mediaAssetRepository;

	@Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
	public Optional<DeletionTarget> getPendingTarget(UUID assetId) {
		return mediaAssetRepository.findById(assetId)
				.filter(asset -> asset.getStatus() == MediaStatus.DELETION_PENDING)
				.map(asset -> new DeletionTarget(
						asset.getId(), asset.getStorageKey(), asset.getKind(), asset.getVisibility(),
						asset.getCreatedAt(), asset.getUploadWriteAuthorityExpiresAt(),
						asset.getDeletionRequestedAt(), asset.getPhysicalDeletionNotBefore(),
						asset.getUpdatedAt(), asset.getUploadVerificationAttemptToken(),
						asset.getUploadVerificationCleanupNotBefore()));
	}

	/** Deletes only the exact durable intent; another state can never be removed. */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public boolean finish(UUID assetId) {
		return mediaAssetRepository.deleteIfStatus(assetId, MediaStatus.DELETION_PENDING) == 1;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void defer(UUID assetId) {
		mediaAssetRepository.deferDeletion(assetId, MediaStatus.DELETION_PENDING);
	}

	public record DeletionTarget(
			UUID assetId,
			String storageKey,
			MediaKind kind,
			MediaVisibility visibility,
			LocalDateTime createdAt,
			LocalDateTime uploadWriteAuthorityExpiresAt,
			LocalDateTime deletionRequestedAt,
			LocalDateTime physicalDeletionNotBefore,
			LocalDateTime updatedAt,
			UUID uploadVerificationAttemptToken,
			LocalDateTime uploadVerificationCleanupNotBefore
	) {
		public DeletionTarget(
				UUID assetId,
				String storageKey,
				MediaKind kind,
				MediaVisibility visibility,
				LocalDateTime createdAt,
				LocalDateTime uploadWriteAuthorityExpiresAt,
				LocalDateTime deletionRequestedAt,
				LocalDateTime physicalDeletionNotBefore,
				LocalDateTime updatedAt
		) {
			this(assetId, storageKey, kind, visibility, createdAt,
					uploadWriteAuthorityExpiresAt, deletionRequestedAt,
					physicalDeletionNotBefore, updatedAt, null, null);
		}

		public DeletionTarget(
				UUID assetId,
				String storageKey,
				MediaKind kind,
				MediaVisibility visibility,
				LocalDateTime createdAt,
				LocalDateTime deletionRequestedAt,
				LocalDateTime updatedAt
		) {
			this(assetId, storageKey, kind, visibility, createdAt, null,
					deletionRequestedAt, null, updatedAt, null, null);
		}
	}
}
