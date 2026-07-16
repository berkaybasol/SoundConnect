package com.berkayb.soundconnect.modules.like.repository;

import com.berkayb.soundconnect.modules.engagement.enums.EngagementTargetType;
import com.berkayb.soundconnect.modules.like.entity.Like;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface LikeRepository extends JpaRepository<Like, UUID> {
	
	// kullanici bu icerigi begenmis mi?
	boolean existsByUserIdAndTargetTypeAndTargetId(UUID userId, EngagementTargetType targetType, UUID targetId);
	
	// begeniyi kaldir(idempotent)
	long deleteByUserIdAndTargetTypeAndTargetId(UUID userId, EngagementTargetType targetType, UUID targetId);
	
	// icerigin toplam begeni sayisi
	long countByTargetTypeAndTargetId(EngagementTargetType targetType, UUID targetId);

	/**
	 * Engagement is subordinate to its target and must not become a hard
	 * reference that prevents the target owner from deleting their content.
	 * The media deletion flow invokes this inside the same row-locked transaction.
	 */
	@Modifying(flushAutomatically = true)
	@Query(value = """
			delete from tbl_like
			where target_type = 'MEDIA' and target_id = :targetId
			""", nativeQuery = true)
	int deleteMediaTargetReferences(@Param("targetId") UUID targetId);
	
	@Query("""
	select l.targetId as targetId, count(l) as count
	from Like l
	where l.targetType = :targetType
	  and l.targetId in :targetIds
	group by l.targetId
	""")
	List<TargetCountProjection> countByTargetTypeAndTargetIdIn(
			@Param("targetType") EngagementTargetType targetType,
			@Param("targetIds") Collection<UUID> targetIds
	);
	
	@Query("""
	select l.targetId
	from Like l
	where l.user.id = :userId
	  and l.targetType = :targetType
	  and l.targetId in :targetIds
	""")
	Set<UUID> findLikedTargetIds(
			@Param("userId") UUID userId,
			@Param("targetType") EngagementTargetType targetType,
			@Param("targetIds") Collection<UUID> targetIds
	);
	
	interface TargetCountProjection {
		UUID getTargetId();
		long getCount();
	}
}
