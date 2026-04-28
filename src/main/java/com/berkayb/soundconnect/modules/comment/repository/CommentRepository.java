package com.berkayb.soundconnect.modules.comment.repository;

import com.berkayb.soundconnect.modules.comment.entity.Comment;
import com.berkayb.soundconnect.modules.comment.repository.projection.CommentReplyCountProjection;
import com.berkayb.soundconnect.modules.engagement.enums.EngagementTargetType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface CommentRepository extends JpaRepository<Comment, UUID> {
	
	// belirli bir icerik uzerindeki root commentleri listeler. parentComment=null olan yorumlar rootdur
	Page<Comment> findByTargetTypeAndTargetIdAndParentCommentIsNull(
			EngagementTargetType targetType,
			UUID targetId,
			Pageable pageable
	);
	
	// belirli bir yorumun reply'lerini getirir.
	Page<Comment> findByParentComment(Comment parentComment, Pageable pageable);
	
	// bir icerikte toplam kac yorum oldugunu getirir.
	long countByTargetTypeAndTargetId(EngagementTargetType targetType, UUID targetId);
	
	// kullanici bir yorum yazmis mi? (silme kontrolu icin gerekli)
	boolean existsByIdAndUserId(UUID id, UUID userId);
	
	// reply sayisini hesaplamak icin gerekli
	long countByParentComment(Comment parentComment);
	
	@Query("""
       select c.parentComment.id as parentCommentId, count(c) as replyCount
       from Comment c
       where c.parentComment.id in :parentIds
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