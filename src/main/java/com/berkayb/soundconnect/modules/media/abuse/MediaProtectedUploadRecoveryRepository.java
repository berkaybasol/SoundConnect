package com.berkayb.soundconnect.modules.media.abuse;

import com.berkayb.soundconnect.modules.media.entity.MediaAsset;
import com.berkayb.soundconnect.modules.media.enums.MediaStatus;
import com.berkayb.soundconnect.modules.media.storage.StorageObjectKeys;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Uses committed READY rows as the durable cleanup intent for protected mutable
 * uploads. Offset paging is deliberate here: recovery does not mutate READY
 * rows, so the scheduler can rotate through the complete ordered set while a
 * failed idempotent delete is retried on the next sweep.
 */
@Repository
public class MediaProtectedUploadRecoveryRepository {

	private final EntityManager entityManager;

	public MediaProtectedUploadRecoveryRepository(EntityManager entityManager) {
		this.entityManager = entityManager;
	}

	@Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
	public List<RecoveryTarget> findReadyBatch(int offset, int limit) {
		if (offset < 0) throw new IllegalArgumentException("offset must not be negative");
		if (limit < 1) throw new IllegalArgumentException("limit must be positive");

		return entityManager.createQuery("""
				select asset from MediaAsset asset
				where asset.status = :readyStatus
				  and asset.storageKey like :privateVerifiedPrefix
				order by asset.createdAt asc, asset.id asc
				""", MediaAsset.class)
				.setParameter("readyStatus", MediaStatus.READY)
				.setParameter("privateVerifiedPrefix", StorageObjectKeys.privateVerifiedPrefix() + "%")
				.setFirstResult(offset)
				.setMaxResults(limit)
				.getResultList()
				.stream()
				.map(asset -> new RecoveryTarget(
						asset.getId(), asset.getStorageKey(), asset.getCreatedAt(),
						asset.getUploadWriteAuthorityExpiresAt()))
				.toList();
	}

	public record RecoveryTarget(
			UUID assetId,
			String privateVerifiedKey,
			LocalDateTime createdAt,
			LocalDateTime uploadWriteAuthorityExpiresAt
	) {
		public RecoveryTarget(UUID assetId, String privateVerifiedKey, LocalDateTime createdAt) {
			this(assetId, privateVerifiedKey, createdAt, null);
		}
	}
}
