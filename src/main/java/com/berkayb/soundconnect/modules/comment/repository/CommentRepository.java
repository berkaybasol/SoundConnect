package com.berkayb.soundconnect.modules.comment.repository;

import com.berkayb.soundconnect.modules.comment.entity.Comment;
import com.berkayb.soundconnect.modules.comment.repository.projection.CommentReplyCountProjection;
import com.berkayb.soundconnect.modules.engagement.enums.EngagementTargetType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface CommentRepository extends JpaRepository<Comment, UUID> {
	@Query(value = """
			select c.id,c.user_id as "userId",c.target_type as "targetType",c.target_id as "targetId",
			       c.parent_comment_id as "parentId",c.is_deleted as "deleted"
			from tbl_comment c where c.id=:id
			""",nativeQuery = true)
	java.util.Optional<LockedComment> findCommentTarget(@Param("id") UUID id);

	@Query(value = """
			select id,user_id as "userId",target_type as "targetType",target_id as "targetId",
			       parent_comment_id as "parentId",is_deleted as "deleted"
			from tbl_comment where id=:id for share
			""",nativeQuery = true)
	java.util.Optional<LockedComment> lockCommentForRead(@Param("id") UUID id);
	@Query(value = """
			select id, user_id as "userId", target_type as "targetType", target_id as "targetId",
			parent_comment_id as "parentId", is_deleted as "deleted"
			from tbl_comment where id=:id for update
			""", nativeQuery = true)
	java.util.Optional<LockedComment> lockComment(@Param("id") UUID id);
	interface LockedComment {
		UUID getId(); UUID getUserId(); String getTargetType(); UUID getTargetId();
		UUID getParentId(); boolean getDeleted();
	}

	@Modifying
	@Query("update Comment c set c.deleted=true,c.updatedAt=CURRENT_TIMESTAMP where c.id=:id")
	int softDelete(@Param("id") UUID id);
	
	// belirli bir icerik uzerindeki root commentleri listeler. parentComment=null olan yorumlar rootdur
	Page<Comment> findByTargetTypeAndTargetIdAndParentCommentIsNull(
			EngagementTargetType targetType,
			UUID targetId,
			Pageable pageable
	);
	
	// belirli bir yorumun reply'lerini getirir.
	@Query("""
			select c from Comment c where c.parentComment = :parent
			and c.targetType = c.parentComment.targetType and c.targetId = c.parentComment.targetId
			""")
	Page<Comment> findByParentComment(@Param("parent") Comment parentComment, Pageable pageable);
	
	// bir icerikte toplam kac yorum oldugunu getirir.
	long countByTargetTypeAndTargetId(EngagementTargetType targetType, UUID targetId);

	/** Delete replies first so the self-referencing parent foreign key stays valid. */
	@Modifying(flushAutomatically = true)
	@Query("""
			delete from Comment c
			where c.targetType = :targetType
			  and c.targetId = :targetId
			  and c.parentComment is not null
			""")
	int deleteRepliesByTarget(
			@Param("targetType") EngagementTargetType targetType,
			@Param("targetId") UUID targetId
	);

	/** Delete roots only after every reply for the target has been removed. */
	@Modifying(flushAutomatically = true)
	@Query("""
			delete from Comment c
			where c.targetType = :targetType
			  and c.targetId = :targetId
			  and c.parentComment is null
			""")
	int deleteRootsByTarget(
			@Param("targetType") EngagementTargetType targetType,
			@Param("targetId") UUID targetId
	);
	
	// kullanici bir yorum yazmis mi? (silme kontrolu icin gerekli)
	boolean existsByIdAndUserId(UUID id, UUID userId);
	
	// reply sayisini hesaplamak icin gerekli
	long countByParentComment(Comment parentComment);
	
	@Query("""
       select c.parentComment.id as parentCommentId, count(c) as replyCount
       from Comment c
       where c.parentComment.id in :parentIds
       and c.targetType = c.parentComment.targetType and c.targetId = c.parentComment.targetId
       group by c.parentComment.id
       """)
	List<CommentReplyCountProjection> countRepliesByParentIds(@Param("parentIds") List<UUID> parentIds);
	
	@Query("""
	select c.targetId as targetId, count(c) as count
	from Comment c
	where c.targetType = :targetType
	  and c.targetId in :targetIds
	group by c.targetId
	""")
	List<TargetCountProjection> countByTargetTypeAndTargetIdIn(
			@Param("targetType") EngagementTargetType targetType,
			@Param("targetIds") Collection<UUID> targetIds
	);
	
	interface TargetCountProjection {
		UUID getTargetId();
		long getCount();
	}
}
