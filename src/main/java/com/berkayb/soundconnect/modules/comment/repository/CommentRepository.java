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
    // The anonymous post owner's comments already have a masked identity. Removing those
    // comments on a block would identify that owner by comparing the two versions.
    String VISIBLE_AUTHOR = """
            (not exists (select 1 from UserBlock b where b.unblockedAt is null
              and ((b.blocker.id=:viewer and b.blocked.id=c.user.id)
                or (b.blocked.id=:viewer and b.blocker.id=c.user.id)))
            or (c.targetType=OVERTHINKING and exists (select 1 from OverthinkingPost post
              where post.id=c.targetId and post.visibilityType=ANONYMOUS and post.author.id=c.user.id)))
            """;
    String VISIBLE_THREAD = """
            (c.parentComment is null or exists (select 1 from Comment root where root=c.parentComment
              and root.targetType=c.targetType and root.targetId=c.targetId
              and (not exists (select 1 from UserBlock b where b.unblockedAt is null
                and ((b.blocker.id=:viewer and b.blocked.id=root.user.id)
                  or (b.blocked.id=:viewer and b.blocker.id=root.user.id)))
                or (root.targetType=OVERTHINKING and exists (select 1 from OverthinkingPost post
                  where post.id=root.targetId and post.visibilityType=ANONYMOUS and post.author.id=root.user.id)))))
            """;

    @Query("select c from Comment c where c.id=:id and " + VISIBLE_AUTHOR)
    java.util.Optional<Comment> findVisibleComment(@Param("viewer") UUID viewer, @Param("id") UUID id);

    @Query("select (count(c)>0) from Comment c where c.id=:id and not " + VISIBLE_AUTHOR)
    boolean hiddenAuthor(@Param("viewer") UUID viewer, @Param("id") UUID id);
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
    @Query("select c from Comment c where c.targetType=:targetType and c.targetId=:targetId and c.parentComment is null and " + VISIBLE_AUTHOR)
    Page<Comment> visibleRoots(
            @Param("viewer") UUID viewer,
            @Param("targetType") EngagementTargetType targetType,
            @Param("targetId") UUID targetId,
			Pageable pageable
	);
	
	// belirli bir yorumun reply'lerini getirir.
	@Query("""
			select c from Comment c where c.parentComment = :parent
			and c.targetType = c.parentComment.targetType and c.targetId = c.parentComment.targetId
            and """ + " " + VISIBLE_AUTHOR)
    Page<Comment> visibleReplies(@Param("viewer") UUID viewer, @Param("parent") Comment parentComment, Pageable pageable);
	
	// bir icerikte toplam kac yorum oldugunu getirir.
	long countByTargetTypeAndTargetId(EngagementTargetType targetType, UUID targetId);

	/** Feed totals include live roots and replies, never soft-deleted placeholders. */
	@Query("""
			select count(c) from Comment c
			where c.targetType = :targetType and c.targetId = :targetId and c.deleted = false
            and """ + " " + VISIBLE_AUTHOR + " and " + VISIBLE_THREAD)
    long countActiveByTarget(@Param("viewer") UUID viewer, @Param("targetType") EngagementTargetType targetType,
	                        @Param("targetId") UUID targetId);

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
       and """ + " " + VISIBLE_AUTHOR + " group by c.parentComment.id")
    List<CommentReplyCountProjection> countRepliesByParentIds(@Param("viewer") UUID viewer, @Param("parentIds") List<UUID> parentIds);
	
	@Query("""
	select c.targetId as targetId, count(c) as count
	from Comment c
	where c.targetType = :targetType
	  and c.targetId in :targetIds
	  and c.deleted = false
    and """ + " " + VISIBLE_AUTHOR + " and " + VISIBLE_THREAD + " group by c.targetId")
    List<TargetCountProjection> countVisibleByTargets(@Param("viewer") UUID viewer,
            @Param("targetType") EngagementTargetType targetType, @Param("targetIds") Collection<UUID> targetIds);
	
	interface TargetCountProjection {
		UUID getTargetId();
		long getCount();
	}
}
