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
	/** No failed-transaction recovery: PostgreSQL handles repeated desired=true atomically. */
	@Modifying
	@Query(value = """
			insert into tbl_like(id,created_at,updated_at,user_id,target_type,target_id)
			values (:id,timezone('UTC',current_timestamp),timezone('UTC',current_timestamp),:userId,:type,:targetId)
			on conflict (user_id,target_type,target_id) do nothing
			""", nativeQuery = true)
	int insertIfAbsent(@Param("id") UUID id,@Param("userId") UUID userId,
	                   @Param("type") String type,@Param("targetId") UUID targetId);

	@Modifying
	@Query(value = "delete from tbl_like where user_id=:userId and target_type=:type and target_id=:targetId",nativeQuery = true)
	int deleteDesiredLike(@Param("userId") UUID userId,@Param("type") String type,@Param("targetId") UUID targetId);

	/** Fresh account eligibility, locked before content/comment locks for comment mutations. */
	@Query(value = "select id from tbl_user where id=:userId and status='ACTIVE' and email_verified=true for share",nativeQuery = true)
	java.util.Optional<UUID> lockActiveActor(@Param("userId") UUID userId);

	/** A single bounded aggregate for the already-authorized comment page, including a guest viewer. */
	@Query(value = """
			select target_id as "targetId",count(*) as "likeCount",
			       coalesce(bool_or(user_id=cast(:viewerId as uuid)),false) as "likedByMe"
			from tbl_like where target_type='COMMENT' and target_id in (:ids) group by target_id
			""",nativeQuery = true)
	List<CommentLikes> commentLikes(@Param("ids") Collection<UUID> ids,@Param("viewerId") UUID viewerId);
	interface CommentLikes {
		UUID getTargetId(); long getLikeCount(); boolean getLikedByMe();
	}

	@Modifying(flushAutomatically = true)
	@Query(value = """
			delete from tbl_like where target_type='COMMENT' and target_id in
			(select id from tbl_comment where target_type='MEDIA' and target_id=:targetId)
			""",nativeQuery = true)
	int deleteMediaCommentReferences(@Param("targetId") UUID targetId);
	
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
