package com.berkayb.soundconnect.modules.social.comment.repository;

import com.berkayb.soundconnect.modules.social.comment.entity.Comment;
import com.berkayb.soundconnect.modules.social.comment.enums.EngagementTargetType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

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
	boolean existByIdAndUserId(UUID commentId, UUID userId);
}