package com.berkayb.soundconnect.modules.social.comment.service;

import com.berkayb.soundconnect.modules.role.enums.PermissionEnum;
import com.berkayb.soundconnect.modules.role.enums.RoleEnum;
import com.berkayb.soundconnect.modules.social.comment.dto.request.CommentCreateRequestDto;
import com.berkayb.soundconnect.modules.social.comment.dto.response.CommentReplyResponseDto;
import com.berkayb.soundconnect.modules.social.comment.dto.response.CommentResponseDto;
import com.berkayb.soundconnect.modules.social.comment.entity.Comment;
import com.berkayb.soundconnect.modules.social.comment.enums.EngagementTargetType;
import com.berkayb.soundconnect.modules.social.comment.mapper.CommentMapper;
import com.berkayb.soundconnect.modules.social.comment.repository.CommentRepository;
import com.berkayb.soundconnect.modules.social.comment.support.CommentEntityFinder;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.support.UserEntityFinder;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class CommentServiceImpl implements CommentService{
	
	private static final int MAX_COMMENT_LENGTH = 2000;
	
	private final CommentRepository commentRepository;
	private final CommentMapper commentMapper;
	private final CommentEntityFinder commentEntityFinder;
	private final UserEntityFinder userEntityFinder;
	
	@Override
	public CommentResponseDto createComment(UUID userId, EngagementTargetType targetType, UUID targetId, CommentCreateRequestDto request) {
		validateCommentText(request.text());
		
		User author = userEntityFinder.getUser(userId);
		
		Comment parent = null;
		if (request.parentCommentId() != null) {
			parent = commentEntityFinder.getById(request.parentCommentId());
			
			//Parent comment farkli bir targeta aitse bu reply'e izin vermiyoz
			if (!parent.getTargetType().equals(targetType) || !parent.getTargetId().equals(targetId)) {
			throw new SoundConnectException(ErrorType.COMMENT_PARENT_TARGET_MISMATCH);
			}
		}
		Comment comment = Comment.builder()
				.user(author)
				.targetType(targetType)
				.targetId(targetId)
				.text(request.text().trim())
				.parentComment(parent)
				.deleted(false)
				.build();
		
		comment = commentRepository.save(comment);
		
		// yeni yaratilan yorumun reply sayisi 0
		return commentMapper.toCommentResponseDto(comment,0);
	}
	
	@Override
	public void deleteComment(UUID userId, UUID commentId) {
		Comment comment = commentEntityFinder.getById(commentId);
		
		// Şimdilik yalnızca owner delete
		if (!comment.getUser().getId().equals(userId)) {
			throw new SoundConnectException(ErrorType.COMMENT_FORBIDDEN);
		}
		
		if (comment.isDeleted()) {
			log.debug("[CommentService] Comment {} already deleted", commentId);
			return;
		}
		
		comment.setDeleted(true);
		log.info("[CommentService] Comment {} soft deleted by user {}", commentId, userId);
	}
	
	@Override
	@Transactional(readOnly = true)
	public Page<CommentResponseDto> getComments(EngagementTargetType targetType, UUID targetId, Pageable pageable) {
		Page<Comment> page = commentRepository.findByTargetTypeAndTargetIdAndParentCommentIsNull(targetType, targetId, pageable);
		
		return page.map(comment -> {
			int replyCount = (int) commentRepository.countByParentComment(comment);
			return commentMapper.toCommentResponseDto(comment, replyCount);
		});
	}
	
	@Override
	@Transactional(readOnly = true)
	public Page<CommentReplyResponseDto> getReplies(UUID parentCommentId, Pageable pageable) {
		Comment parent = commentEntityFinder.getById(parentCommentId);
		
		Page<Comment> replies = commentRepository.findByParentComment(parent, pageable);
		
		return replies.map(commentMapper::toCommentReplyResponseDto);
	}
	
	@Override
	@Transactional(readOnly = true)
	public long countComments(EngagementTargetType targetType, UUID targetId) {
		return commentRepository.countByTargetTypeAndTargetId(targetType, targetId);
	}
	
	
	// helper
	private void validateCommentText(String text) {
		if (text == null || text.trim().isEmpty()) {
			throw new SoundConnectException(ErrorType.COMMENT_TEXT_INVALID);
		}
		String trimmed = text.trim();
		if (trimmed.length() > MAX_COMMENT_LENGTH) {
			throw new SoundConnectException(ErrorType.COMMENT_TEXT_INVALID);
		}
	}
}