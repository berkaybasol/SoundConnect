package com.berkayb.soundconnect.modules.media.repository;

import com.berkayb.soundconnect.modules.media.entity.MediaAsset;
import com.berkayb.soundconnect.modules.media.enums.MediaKind;
import com.berkayb.soundconnect.modules.media.enums.MediaOwnerType;
import com.berkayb.soundconnect.modules.media.enums.MediaStatus;
import com.berkayb.soundconnect.modules.media.enums.MediaStreamingProtocol;
import com.berkayb.soundconnect.modules.media.enums.MediaVisibility;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MediaAssetRepository extends JpaRepository<MediaAsset, UUID> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select asset from MediaAsset asset where asset.id = :assetId")
	Optional<MediaAsset> findByIdForUpdate(@Param("assetId") UUID assetId);

	List<MediaAsset> findByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
			MediaStatus status,
			LocalDateTime cutoff,
			Pageable pageable
	);

	List<MediaAsset> findByStatusOrderByUpdatedAtAsc(MediaStatus status, Pageable pageable);

	@Query("""
			select asset.id from MediaAsset asset
			where asset.kind = :kind
			  and asset.visibility = :visibility
			  and asset.status = :status
			  and (asset.thumbnailUrl is null or trim(asset.thumbnailUrl) = '')
			  and asset.storageKey is not null
			order by asset.createdAt asc, asset.id asc
			""")
	List<UUID> findIdsMissingThumbnail(
			@Param("kind") MediaKind kind,
			@Param("visibility") MediaVisibility visibility,
			@Param("status") MediaStatus status,
			Pageable pageable
	);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update MediaAsset asset
			set asset.thumbnailUrl = :thumbnailUrl,
			    asset.width = :sourceWidth,
			    asset.height = :sourceHeight,
			    asset.updatedAt = CURRENT_TIMESTAMP
			where asset.id = :assetId
			  and asset.kind = :kind
			  and asset.visibility = :visibility
			  and asset.status = :status
			  and asset.storageKey = :expectedSourceKey
			  and (asset.thumbnailUrl is null or trim(asset.thumbnailUrl) = '')
			""")
	int attachImageThumbnailIfEligible(
			@Param("assetId") UUID assetId,
			@Param("kind") MediaKind kind,
			@Param("visibility") MediaVisibility visibility,
			@Param("status") MediaStatus status,
			@Param("expectedSourceKey") String expectedSourceKey,
			@Param("thumbnailUrl") String thumbnailUrl,
			@Param("sourceWidth") Integer sourceWidth,
			@Param("sourceHeight") Integer sourceHeight
	);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update MediaAsset asset
			set asset.status = :cleanupStatus,
			    asset.updatedAt = CURRENT_TIMESTAMP
			where asset.id = :assetId
			  and asset.status = :uploadingStatus
			  and asset.createdAt < :cutoff
			""")
	int claimStaleUploadForCleanup(
			@Param("assetId") UUID assetId,
			@Param("uploadingStatus") MediaStatus uploadingStatus,
			@Param("cleanupStatus") MediaStatus cleanupStatus,
			@Param("cutoff") LocalDateTime cutoff
	);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update MediaAsset asset
			set asset.status = :failedStatus,
			    asset.storageKey = null,
			    asset.sourceUrl = null,
			    asset.updatedAt = CURRENT_TIMESTAMP
			where asset.id = :assetId
			  and asset.status = :cleanupStatus
			  and asset.storageKey = :storageKey
			""")
	int finishUploadCleanup(
			@Param("assetId") UUID assetId,
			@Param("storageKey") String storageKey,
			@Param("cleanupStatus") MediaStatus cleanupStatus,
			@Param("failedStatus") MediaStatus failedStatus
	);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update MediaAsset asset
			set asset.status = :failedStatus,
			    asset.sourceUrl = null,
			    asset.updatedAt = CURRENT_TIMESTAMP
			where asset.id = :assetId
			  and asset.status = :cleanupStatus
			  and asset.storageKey is null
			""")
	int finishUploadCleanupWithoutStorage(
			@Param("assetId") UUID assetId,
			@Param("cleanupStatus") MediaStatus cleanupStatus,
			@Param("failedStatus") MediaStatus failedStatus
	);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update MediaAsset asset
			set asset.updatedAt = CURRENT_TIMESTAMP
			where asset.id = :assetId
			  and asset.status = :cleanupStatus
			""")
	int deferUploadCleanup(
			@Param("assetId") UUID assetId,
			@Param("cleanupStatus") MediaStatus cleanupStatus
	);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("delete from MediaAsset asset where asset.id = :assetId and asset.status = :pendingStatus")
	int deleteIfStatus(
			@Param("assetId") UUID assetId,
			@Param("pendingStatus") MediaStatus pendingStatus
	);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update MediaAsset asset
			set asset.updatedAt = CURRENT_TIMESTAMP
			where asset.id = :assetId
			  and asset.status = :pendingStatus
			""")
	int deferDeletion(
			@Param("assetId") UUID assetId,
			@Param("pendingStatus") MediaStatus pendingStatus
	);

	Page<MediaAsset> findByKindAndVisibilityAndStatusOrderByCreatedAtAsc(
			MediaKind kind,
			MediaVisibility visibility,
			MediaStatus status,
			Pageable pageable
	);

	List<MediaAsset> findByKindAndVisibilityAndStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(
			MediaKind kind,
			MediaVisibility visibility,
			MediaStatus status,
			LocalDateTime cutoff,
			Pageable pageable
	);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update MediaAsset asset
			set asset.status = :sentStatus,
			    asset.updatedAt = CURRENT_TIMESTAMP
			where asset.id = :assetId
			  and asset.kind = :kind
			  and asset.visibility = :visibility
			  and asset.status = :queuedStatus
			""")
	int markTranscodeSent(
			@Param("assetId") UUID assetId,
			@Param("kind") MediaKind kind,
			@Param("visibility") MediaVisibility visibility,
			@Param("queuedStatus") MediaStatus queuedStatus,
			@Param("sentStatus") MediaStatus sentStatus
	);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update MediaAsset asset
			set asset.status = :queuedStatus,
			    asset.updatedAt = CURRENT_TIMESTAMP
			where asset.id = :assetId
			  and asset.kind = :kind
			  and asset.visibility = :visibility
			  and asset.status = :sentStatus
			  and asset.updatedAt < :cutoff
			""")
	int requeueStaleSentTranscode(
			@Param("assetId") UUID assetId,
			@Param("kind") MediaKind kind,
			@Param("visibility") MediaVisibility visibility,
			@Param("sentStatus") MediaStatus sentStatus,
			@Param("queuedStatus") MediaStatus queuedStatus,
			@Param("cutoff") LocalDateTime cutoff
	);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update MediaAsset asset
			set asset.status = :queuedStatus,
			    asset.updatedAt = CURRENT_TIMESTAMP
			where asset.id = :assetId
			  and asset.kind = :kind
			  and asset.visibility = :visibility
			  and asset.status = :sentStatus
			""")
	int requeueUnclaimedTranscodeSignal(
			@Param("assetId") UUID assetId,
			@Param("kind") MediaKind kind,
			@Param("visibility") MediaVisibility visibility,
			@Param("sentStatus") MediaStatus sentStatus,
			@Param("queuedStatus") MediaStatus queuedStatus
	);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update MediaAsset asset
			set asset.status = :processingStatus,
			    asset.transcodeAttemptToken = :attemptToken,
			    asset.transcodeLeaseUntil = :leaseUntil,
			    asset.transcodeAttemptDeadline = :attemptDeadline,
			    asset.transcodeCleanupNotBefore = null,
			    asset.transcodeAttemptCount = asset.transcodeAttemptCount + 1,
			    asset.transcodeRetryPending = false,
			    asset.transcodeRetainSourceAfterCleanup = false,
			    asset.updatedAt = CURRENT_TIMESTAMP
			where asset.id = :assetId
			  and asset.kind = :kind
			  and asset.visibility = :visibility
			  and asset.status in (:queuedStatus, :sentStatus)
			  and asset.transcodeAttemptCount < :maxAttempts
			""")
	int claimQueuedTranscode(
			@Param("assetId") UUID assetId,
			@Param("kind") MediaKind kind,
			@Param("visibility") MediaVisibility visibility,
			@Param("queuedStatus") MediaStatus queuedStatus,
			@Param("sentStatus") MediaStatus sentStatus,
			@Param("processingStatus") MediaStatus processingStatus,
			@Param("attemptToken") UUID attemptToken,
			@Param("leaseUntil") LocalDateTime leaseUntil,
			@Param("attemptDeadline") LocalDateTime attemptDeadline,
			@Param("maxAttempts") int maxAttempts
	);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update MediaAsset asset
			set asset.transcodeLeaseUntil = :newLeaseUntil,
			    asset.updatedAt = CURRENT_TIMESTAMP
			where asset.id = :assetId
			  and asset.kind = :kind
			  and asset.visibility = :visibility
			  and asset.status = :processingStatus
			  and asset.transcodeAttemptToken = :attemptToken
			  and asset.transcodeLeaseUntil > :now
			  and asset.transcodeAttemptDeadline > :now
			""")
	int renewTranscodeLease(
			@Param("assetId") UUID assetId,
			@Param("kind") MediaKind kind,
			@Param("visibility") MediaVisibility visibility,
			@Param("processingStatus") MediaStatus processingStatus,
			@Param("attemptToken") UUID attemptToken,
			@Param("now") LocalDateTime now,
			@Param("newLeaseUntil") LocalDateTime newLeaseUntil
	);

	/**
	 * Publishes HLS metadata only while the exact transcode claim is still owned
	 * by the worker. A concurrent delete changes the status first and therefore
	 * makes this compare-and-set return {@code 0} without resurrecting the row.
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update MediaAsset asset
			set asset.playbackUrl = :playbackUrl,
			    asset.thumbnailUrl = :thumbnailUrl,
			    asset.durationSeconds = :durationSeconds,
			    asset.width = :width,
			    asset.height = :height,
			    asset.streamingProtocol = :streamingProtocol,
			    asset.status = :readyStatus,
			    asset.transcodeAttemptToken = null,
			    asset.transcodeLeaseUntil = null,
			    asset.transcodeAttemptDeadline = null,
			    asset.transcodeCleanupNotBefore = null,
			    asset.transcodeRetryPending = false,
			    asset.transcodeRetainSourceAfterCleanup = false,
			    asset.updatedAt = CURRENT_TIMESTAMP
			where asset.id = :assetId
			  and asset.kind = :kind
			  and asset.visibility = :visibility
			  and asset.status = :processingStatus
			  and asset.transcodeAttemptToken = :attemptToken
			  and asset.transcodeLeaseUntil > :now
			  and asset.transcodeAttemptDeadline > :now
			""")
	int finalizeHlsIfProcessing(
			@Param("assetId") UUID assetId,
			@Param("kind") MediaKind kind,
			@Param("visibility") MediaVisibility visibility,
			@Param("processingStatus") MediaStatus processingStatus,
			@Param("attemptToken") UUID attemptToken,
			@Param("now") LocalDateTime now,
			@Param("readyStatus") MediaStatus readyStatus,
			@Param("streamingProtocol") MediaStreamingProtocol streamingProtocol,
			@Param("playbackUrl") String playbackUrl,
			@Param("thumbnailUrl") String thumbnailUrl,
			@Param("durationSeconds") Integer durationSeconds,
			@Param("width") Integer width,
			@Param("height") Integer height
	);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update MediaAsset asset
			set asset.status = :cleanupStatus,
			    asset.transcodeAttemptToken = null,
			    asset.transcodeLeaseUntil = null,
			    asset.transcodeRetryPending = false,
			    asset.transcodeRetainSourceAfterCleanup = false,
			    asset.updatedAt = CURRENT_TIMESTAMP
			where asset.id = :assetId
			  and asset.kind = :kind
			  and asset.visibility = :visibility
			  and asset.status = :processingStatus
			  and asset.transcodeAttemptToken = :attemptToken
			  and asset.transcodeLeaseUntil > :now
			  and asset.transcodeAttemptDeadline > :now
			""")
	int markTranscodeAttemptForCleanup(
			@Param("assetId") UUID assetId,
			@Param("kind") MediaKind kind,
			@Param("visibility") MediaVisibility visibility,
			@Param("processingStatus") MediaStatus processingStatus,
			@Param("cleanupStatus") MediaStatus cleanupStatus,
			@Param("attemptToken") UUID attemptToken,
			@Param("now") LocalDateTime now
	);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update MediaAsset asset
			set asset.status = :cleanupStatus,
			    asset.transcodeAttemptToken = null,
			    asset.transcodeLeaseUntil = null,
			    asset.transcodeCleanupNotBefore = :cleanupNotBefore,
			    asset.transcodeRetryPending = true,
			    asset.transcodeRetainSourceAfterCleanup = false,
			    asset.updatedAt = CURRENT_TIMESTAMP
			where asset.id = :assetId
			  and asset.kind = :kind
			  and asset.visibility = :visibility
			  and asset.status = :processingStatus
			  and asset.transcodeAttemptToken = :attemptToken
			  and asset.transcodeLeaseUntil > :now
			  and asset.transcodeAttemptDeadline > :now
			  and asset.transcodeAttemptCount = :attemptNumber
			  and asset.transcodeAttemptCount < :maxAttempts
			""")
	int markTranscodeAttemptForRetryCleanup(
			@Param("assetId") UUID assetId,
			@Param("kind") MediaKind kind,
			@Param("visibility") MediaVisibility visibility,
			@Param("processingStatus") MediaStatus processingStatus,
			@Param("cleanupStatus") MediaStatus cleanupStatus,
			@Param("attemptToken") UUID attemptToken,
			@Param("attemptNumber") int attemptNumber,
			@Param("maxAttempts") int maxAttempts,
			@Param("now") LocalDateTime now,
			@Param("cleanupNotBefore") LocalDateTime cleanupNotBefore
	);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update MediaAsset asset
			set asset.status = :cleanupStatus,
			    asset.transcodeAttemptToken = null,
			    asset.transcodeLeaseUntil = null,
			    asset.transcodeCleanupNotBefore = null,
			    asset.transcodeRetryPending = false,
			    asset.transcodeRetainSourceAfterCleanup = true,
			    asset.updatedAt = CURRENT_TIMESTAMP
			where asset.id = :assetId
			  and asset.kind = :kind
			  and asset.visibility = :visibility
			  and asset.status = :processingStatus
			  and asset.transcodeAttemptToken = :attemptToken
			  and asset.transcodeLeaseUntil > :now
			  and asset.transcodeAttemptDeadline > :now
			  and asset.transcodeAttemptCount = :attemptNumber
			  and asset.transcodeAttemptCount >= :maxAttempts
			""")
	int markTranscodeAttemptForRetainedCleanup(
			@Param("assetId") UUID assetId,
			@Param("kind") MediaKind kind,
			@Param("visibility") MediaVisibility visibility,
			@Param("processingStatus") MediaStatus processingStatus,
			@Param("cleanupStatus") MediaStatus cleanupStatus,
			@Param("attemptToken") UUID attemptToken,
			@Param("attemptNumber") int attemptNumber,
			@Param("maxAttempts") int maxAttempts,
			@Param("now") LocalDateTime now
	);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update MediaAsset asset
			set asset.status = :cleanupStatus,
			    asset.transcodeAttemptToken = null,
			    asset.transcodeLeaseUntil = null,
			    asset.transcodeCleanupNotBefore = asset.transcodeAttemptDeadline,
			    asset.transcodeRetryPending = true,
			    asset.transcodeRetainSourceAfterCleanup = false,
			    asset.updatedAt = CURRENT_TIMESTAMP
			where asset.kind = :kind
			  and asset.visibility = :visibility
			  and asset.status = :processingStatus
			  and (asset.transcodeLeaseUntil is null or asset.transcodeLeaseUntil <= :now)
			  and asset.transcodeAttemptCount < :maxAttempts
			""")
	int recoverExpiredTranscodesForRetry(
			@Param("kind") MediaKind kind,
			@Param("visibility") MediaVisibility visibility,
			@Param("processingStatus") MediaStatus processingStatus,
			@Param("cleanupStatus") MediaStatus cleanupStatus,
			@Param("now") LocalDateTime now,
			@Param("maxAttempts") int maxAttempts
	);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update MediaAsset asset
			set asset.status = :cleanupStatus,
			    asset.transcodeAttemptToken = null,
			    asset.transcodeLeaseUntil = null,
			    asset.transcodeCleanupNotBefore = asset.transcodeAttemptDeadline,
			    asset.transcodeRetryPending = false,
			    asset.transcodeRetainSourceAfterCleanup = true,
			    asset.updatedAt = CURRENT_TIMESTAMP
			where asset.kind = :kind
			  and asset.visibility = :visibility
			  and asset.status = :processingStatus
			  and (asset.transcodeLeaseUntil is null or asset.transcodeLeaseUntil <= :now)
			  and asset.transcodeAttemptCount >= :maxAttempts
			""")
	int recoverExhaustedTranscodesForCleanup(
			@Param("kind") MediaKind kind,
			@Param("visibility") MediaVisibility visibility,
			@Param("processingStatus") MediaStatus processingStatus,
			@Param("cleanupStatus") MediaStatus cleanupStatus,
			@Param("now") LocalDateTime now,
			@Param("maxAttempts") int maxAttempts
	);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update MediaAsset asset
			set asset.status = :failedStatus,
			    asset.storageKey = null,
			    asset.sourceUrl = null,
			    asset.playbackUrl = null,
			    asset.thumbnailUrl = null,
			    asset.transcodeAttemptToken = null,
			    asset.transcodeLeaseUntil = null,
			    asset.transcodeAttemptDeadline = null,
			    asset.transcodeCleanupNotBefore = null,
			    asset.transcodeRetryPending = false,
			    asset.transcodeRetainSourceAfterCleanup = false,
			    asset.updatedAt = CURRENT_TIMESTAMP
			where asset.id = :assetId
			  and asset.kind = :kind
			  and asset.visibility = :visibility
			  and asset.status = :cleanupStatus
			""")
	int completeHlsCleanup(
			@Param("assetId") UUID assetId,
			@Param("kind") MediaKind kind,
			@Param("visibility") MediaVisibility visibility,
			@Param("cleanupStatus") MediaStatus cleanupStatus,
			@Param("failedStatus") MediaStatus failedStatus
	);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update MediaAsset asset
			set asset.status = :queuedStatus,
			    asset.playbackUrl = null,
			    asset.thumbnailUrl = null,
			    asset.transcodeAttemptToken = null,
			    asset.transcodeLeaseUntil = null,
			    asset.transcodeAttemptDeadline = null,
			    asset.transcodeCleanupNotBefore = null,
			    asset.transcodeRetryPending = false,
			    asset.transcodeRetainSourceAfterCleanup = false,
			    asset.updatedAt = CURRENT_TIMESTAMP
			where asset.id = :assetId
			  and asset.kind = :kind
			  and asset.visibility = :visibility
			  and asset.status = :cleanupStatus
			  and asset.transcodeRetryPending = true
			  and asset.transcodeAttemptCount < :maxAttempts
			""")
	int completeHlsRetryCleanup(
			@Param("assetId") UUID assetId,
			@Param("kind") MediaKind kind,
			@Param("visibility") MediaVisibility visibility,
			@Param("cleanupStatus") MediaStatus cleanupStatus,
			@Param("queuedStatus") MediaStatus queuedStatus,
			@Param("maxAttempts") int maxAttempts
	);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update MediaAsset asset
			set asset.transcodeRetryPending = false,
			    asset.transcodeCleanupNotBefore = null,
			    asset.transcodeRetainSourceAfterCleanup = true,
			    asset.updatedAt = CURRENT_TIMESTAMP
			where asset.id = :assetId
			  and asset.kind = :kind
			  and asset.visibility = :visibility
			  and asset.status = :cleanupStatus
			  and asset.transcodeRetryPending = true
			  and asset.transcodeAttemptCount >= :maxAttempts
			""")
	int exhaustHlsRetryCleanup(
			@Param("assetId") UUID assetId,
			@Param("kind") MediaKind kind,
			@Param("visibility") MediaVisibility visibility,
			@Param("cleanupStatus") MediaStatus cleanupStatus,
			@Param("maxAttempts") int maxAttempts
	);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update MediaAsset asset
			set asset.status = :failedStatus,
			    asset.sourceUrl = null,
			    asset.playbackUrl = null,
			    asset.thumbnailUrl = null,
			    asset.durationSeconds = null,
			    asset.width = null,
			    asset.height = null,
			    asset.transcodeAttemptToken = null,
			    asset.transcodeLeaseUntil = null,
			    asset.transcodeAttemptDeadline = null,
			    asset.transcodeCleanupNotBefore = null,
			    asset.transcodeRetryPending = false,
			    asset.transcodeRetainSourceAfterCleanup = false,
			    asset.updatedAt = CURRENT_TIMESTAMP
			where asset.id = :assetId
			  and asset.kind = :kind
			  and asset.visibility = :visibility
			  and asset.status = :cleanupStatus
			  and asset.transcodeRetryPending = false
			  and asset.transcodeRetainSourceAfterCleanup = true
			""")
	int completeRetainedSourceFailure(
			@Param("assetId") UUID assetId,
			@Param("kind") MediaKind kind,
			@Param("visibility") MediaVisibility visibility,
			@Param("cleanupStatus") MediaStatus cleanupStatus,
			@Param("failedStatus") MediaStatus failedStatus
	);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update MediaAsset asset
			set asset.updatedAt = CURRENT_TIMESTAMP
			where asset.id = :assetId
			  and asset.kind = :kind
			  and asset.visibility = :visibility
			  and asset.status = :cleanupStatus
			""")
	int deferHlsCleanup(
			@Param("assetId") UUID assetId,
			@Param("kind") MediaKind kind,
			@Param("visibility") MediaVisibility visibility,
			@Param("cleanupStatus") MediaStatus cleanupStatus
	);

	// sahibin tum medyasi
	Page<MediaAsset> findByOwnerTypeAndOwnerId(MediaOwnerType ownerType, UUID ownerId, Pageable pageable);
	
	// sahibin belirli ture gore medyasi
	Page<MediaAsset> findByOwnerTypeAndOwnerIdAndKind(
			MediaOwnerType ownerType,
			UUID ownerId,
			MediaKind kind,
			Pageable pageable
	);
	
	// sahibin public + ready medyalari
	Page<MediaAsset> findByOwnerTypeAndOwnerIdAndVisibilityAndStatus(
			MediaOwnerType ownerType,
			UUID ownerId,
			MediaVisibility visibility,
			MediaStatus status,
			Pageable pageable
	);
	
	//sahibin public + ready + tur bazli medyalari
	Page<MediaAsset> findByOwnerTypeAndOwnerIdAndKindAndVisibilityAndStatus(
			MediaOwnerType ownerType,
			UUID ownerId,
			MediaKind kind,
			MediaVisibility visibility,
			MediaStatus status,
			Pageable pageable
	);
	
	// kota/istatistik icin
	long countByOwnerTypeAndOwnerId(MediaOwnerType ownerType, UUID ownerId);
	
	// sistemdeki public + ready medyalar
	Page<MediaAsset> findByVisibilityAndStatus(
			MediaVisibility visibility,
			MediaStatus status,
			Pageable pageable
	);
	
	// sistemdeki public + ready + tur bazli medyalar
	Page<MediaAsset> findByVisibilityAndStatusAndKind(
			MediaVisibility visibility,
			MediaStatus status,
			MediaKind kind,
			Pageable pageable
	);
}
