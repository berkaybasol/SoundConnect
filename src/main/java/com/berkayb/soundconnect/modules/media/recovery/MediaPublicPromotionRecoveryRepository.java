package com.berkayb.soundconnect.modules.media.recovery;

import com.berkayb.soundconnect.modules.media.enums.MediaKind;
import com.berkayb.soundconnect.modules.media.enums.MediaStatus;
import com.berkayb.soundconnect.modules.media.enums.MediaStreamingProtocol;
import com.berkayb.soundconnect.modules.media.enums.MediaVisibility;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Reads only the fields needed for recovery. READY rows are the durable intent:
 * recovery never mutates them, so an object-store failure remains retryable
 * after both worker and process restarts.
 */
@Repository
public class MediaPublicPromotionRecoveryRepository {

	private final EntityManager entityManager;

	public MediaPublicPromotionRecoveryRepository(EntityManager entityManager) {
		this.entityManager = entityManager;
	}

	@Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
	public List<MediaPublicPromotionRecoveryTarget> findReadyBatch(int offset, int limit) {
		if (offset < 0) throw new IllegalArgumentException("offset must not be negative");
		if (limit < 1) throw new IllegalArgumentException("limit must be positive");

		return entityManager.createQuery("""
				select new com.berkayb.soundconnect.modules.media.recovery.MediaPublicPromotionRecoveryTarget(
						asset.id, asset.storageKey, asset.createdAt,
						asset.uploadWriteAuthorityExpiresAt,
						asset.uploadVerificationAttemptToken)
				from MediaAsset asset
				where asset.status = :readyStatus
				  and asset.visibility = :publicVisibility
				  and asset.streamingProtocol = :progressiveProtocol
				  and asset.kind in :progressiveKinds
				  and asset.storageKey is not null
				order by asset.createdAt asc, asset.id asc
				""", MediaPublicPromotionRecoveryTarget.class)
				.setParameter("readyStatus", MediaStatus.READY)
				.setParameter("publicVisibility", MediaVisibility.PUBLIC)
				.setParameter("progressiveProtocol", MediaStreamingProtocol.PROGRESSIVE)
				.setParameter("progressiveKinds", List.of(MediaKind.IMAGE, MediaKind.AUDIO))
				.setFirstResult(offset)
				.setMaxResults(limit)
				.getResultList();
	}
}
