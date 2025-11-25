package com.berkayb.soundconnect.modules.social.comment.service;

import com.berkayb.soundconnect.modules.social.comment.dto.request.CommentCreateRequestDto;
import com.berkayb.soundconnect.modules.social.comment.dto.response.CommentReplyResponseDto;
import com.berkayb.soundconnect.modules.social.comment.dto.response.CommentResponseDto;
import com.berkayb.soundconnect.modules.social.comment.enums.EngagementTargetType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface CommentService {
	
	
	// yorum olusturur
	CommentResponseDto createComment(UUID userId, EngagementTargetType targetType, UUID targetId, CommentCreateRequestDto request);
	
	// yorum sil
	void deleteComment(UUID userId, UUID commentId);
	
	// belirli bir icerik uzerindeki root yorumlari doner
	Page<CommentResponseDto> getComments(
			EngagementTargetType targetType,
			UUID targetId,
			Pageable pageable
	);
	
	// belirli bir yorumun reply'lerini doner
	Page<CommentReplyResponseDto> getReplies(UUID parentCommentId, Pageable pageable);
	
	// belirli bir icerik uzerindeki toplam yorum sayisini doner
	long countComments(EngagementTargetType targetType, UUID targetId);
	
}