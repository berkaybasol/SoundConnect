package com.berkayb.soundconnect.modules.media.verification;

import com.berkayb.soundconnect.modules.media.enums.MediaStatus;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class MediaUploadVerificationOrphanRepository {

	private static final List<MediaStatus> ACTIVE_OR_DESTRUCTIVE = List.of(
			MediaStatus.UPLOADING,
			MediaStatus.VERIFYING,
			MediaStatus.CLEANUP_PENDING,
			MediaStatus.DELETION_PENDING
	);

	private final EntityManager entityManager;

	@Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
	public List<MediaUploadVerificationOrphanTarget> findEligibleBatch(
			LocalDateTime now,
			int limit
	) {
		return entityManager.createQuery("""
				select new com.berkayb.soundconnect.modules.media.verification.MediaUploadVerificationOrphanTarget(
						asset.id, asset.storageKey, asset.uploadVerificationCleanupNotBefore)
				from MediaAsset asset
				where asset.uploadVerificationCleanupNotBefore is not null
				  and asset.uploadVerificationCleanupNotBefore <= :now
				  and asset.storageKey is not null
				  and asset.status not in :excludedStatuses
				order by asset.uploadVerificationCleanupNotBefore asc, asset.id asc
				""", MediaUploadVerificationOrphanTarget.class)
				.setParameter("now", now)
				.setParameter("excludedStatuses", ACTIVE_OR_DESTRUCTIVE)
				.setMaxResults(Math.max(1, Math.min(limit, 500)))
				.getResultList();
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public boolean markSwept(MediaUploadVerificationOrphanTarget target) {
		return entityManager.createQuery("""
				update MediaAsset asset
				set asset.uploadVerificationCleanupNotBefore = null
				where asset.id = :assetId
				  and asset.storageKey = :storageKey
				  and asset.uploadVerificationCleanupNotBefore = :cleanupNotBefore
				  and asset.status not in :excludedStatuses
				""")
				.setParameter("assetId", target.assetId())
				.setParameter("storageKey", target.currentStorageKey())
				.setParameter("cleanupNotBefore", target.cleanupNotBefore())
				.setParameter("excludedStatuses", ACTIVE_OR_DESTRUCTIVE)
				.executeUpdate() == 1;
	}
}
