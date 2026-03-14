package com.berkayb.soundconnect.modules.comment.service;

import com.berkayb.soundconnect.modules.comment.dto.request.CommentCreateRequestDto;
import com.berkayb.soundconnect.modules.comment.dto.response.CommentReplyResponseDto;
import com.berkayb.soundconnect.modules.comment.dto.response.CommentResponseDto;
import com.berkayb.soundconnect.modules.comment.entity.Comment;
import com.berkayb.soundconnect.modules.engagement.enums.EngagementTargetType;
import com.berkayb.soundconnect.modules.comment.mapper.CommentMapper;
import com.berkayb.soundconnect.modules.comment.repository.CommentRepository;
import com.berkayb.soundconnect.modules.comment.support.CommentEntityFinder;
import com.berkayb.soundconnect.modules.engagement.service.EngagementTargetValidator;
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

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class CommentServiceImpl implements CommentService{
	
	private static final int DEFAULT_PAGE_NUMBER = 0;
	private static final int DEFAULT_PAGE_SIZE = 10;
	private static final int MAX_PAGE_SIZE = 50;
	private static final String CREATED_AT = "createdAt";
	
	private final EngagementTargetValidator engagementTargetValidator;
	
	private static final int MAX_COMMENT_LENGTH = 2000;
	private static final String DELETED_COMMENT_PLACEHOLDER = "[Bu yorum silinmiştir]";
	
	private final CommentRepository commentRepository;
	private final CommentMapper commentMapper;
	private final CommentEntityFinder commentEntityFinder;
	private final UserEntityFinder userEntityFinder;
	
	@Override
	public CommentResponseDto createComment(UUID userId, EngagementTargetType targetType, UUID targetId, CommentCreateRequestDto request) {
		validateCommentText(request.text());
		
		engagementTargetValidator.validateExists(targetType, targetId);
		
		User author = userEntityFinder.getUser(userId);
		
		Comment parent = null;
		if (request.parentCommentId() != null) {
			parent = commentEntityFinder.getById(request.parentCommentId());
			
			if (parent.isDeleted()) {
				throw new SoundConnectException(ErrorType.COMMENT_PARENT_DELETED);
			}
			
			if (parent.getParentComment() != null) {
				throw new SoundConnectException(ErrorType.COMMENT_REPLY_DEPTH_NOT_ALLOWED);
			}
			
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
		Pageable safePageable = buildCommentPageable(pageable);
		
		Page<Comment> page = commentRepository.findByTargetTypeAndTargetIdAndParentCommentIsNull(
				targetType,
				targetId,
				safePageable
		);
		
		Map<UUID,Integer> replyCountMap = getReplyCountMap(page.getContent());
		
		return page.map(comment -> {
			int replyCount = replyCountMap.getOrDefault(comment.getId(), 0);
			return mapToCommentResponse(comment, replyCount);
		});
	}
	
	@Override
	@Transactional(readOnly = true)
	public Page<CommentReplyResponseDto> getReplies(UUID parentCommentId, Pageable pageable) {
		Comment parent = commentEntityFinder.getById(parentCommentId);
		
		Pageable safePageable = buildReplyPageable(pageable);
		
		Page<Comment> replies = commentRepository.findByParentComment(parent, safePageable);
		
		return replies.map(this::mapToReplyResponse);
	}
	
	@Override
	@Transactional(readOnly = true)
	public long countComments(EngagementTargetType targetType, UUID targetId) {
		return commentRepository.countByTargetTypeAndTargetId(targetType, targetId);
	}
	
	
	// helpers
	
	private void validateCommentText(String text) {
		if (text == null || text.trim().isEmpty()) {
			throw new SoundConnectException(ErrorType.COMMENT_TEXT_INVALID);
		}
		String trimmed = text.trim();
		if (trimmed.length() > MAX_COMMENT_LENGTH) {
			throw new SoundConnectException(ErrorType.COMMENT_TEXT_INVALID);
		}
	}
	
	private CommentResponseDto mapToCommentResponse(Comment comment, int replyCount) {
		CommentResponseDto dto = commentMapper.toCommentResponseDto(comment, replyCount);
		
		if (comment.isDeleted()) {
			return new CommentResponseDto(
					dto.id(),
					dto.user(),
					DELETED_COMMENT_PLACEHOLDER,
					true,
					dto.parentCommentId(),
					dto.replyCount(),
					dto.createdAt()
			);
		}
		
		return dto;
	}
	
	private CommentReplyResponseDto mapToReplyResponse(Comment comment) {
		CommentReplyResponseDto dto = commentMapper.toCommentReplyResponseDto(comment);
		
		if (comment.isDeleted()) {
			return new CommentReplyResponseDto(
					dto.id(),
					dto.user(),
					DELETED_COMMENT_PLACEHOLDER,
					true,
					dto.parentCommentId(),
					dto.createdAt()
			);
		}
		
		return dto;
	}
	
	private Map<UUID, Integer> getReplyCountMap(List<Comment> comments) {
		List<UUID> parentIds = comments.stream()
		                                         .map(Comment::getId)
		                                         .toList();
		
		if (parentIds.isEmpty()) {
			return Collections.emptyMap();
		}
		
		return commentRepository.countRepliesByParentIds(parentIds).stream()
		                        .collect(Collectors.toMap(
				                        projection -> projection.getParentCommentId(),
				                        projection -> Math.toIntExact(projection.getReplyCount())
		                        ));
	}
	
	private Pageable buildCommentPageable(Pageable pageable) {
		int page = pageable != null ? Math.max(pageable.getPageNumber(), 0) : DEFAULT_PAGE_NUMBER;
		int size = pageable != null ? Math.min(Math.max(pageable.getPageSize(), 1), MAX_PAGE_SIZE) : DEFAULT_PAGE_SIZE;
		
		return org.springframework.data.domain.PageRequest.of(
				page,
				size,
				org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, CREATED_AT)
		);
	}
	
	private Pageable buildReplyPageable(Pageable pageable) {
		int page = pageable != null ? Math.max(pageable.getPageNumber(), 0) : DEFAULT_PAGE_NUMBER;
		int size = pageable != null ? Math.min(Math.max(pageable.getPageSize(), 1), MAX_PAGE_SIZE) : DEFAULT_PAGE_SIZE;
		
		return org.springframework.data.domain.PageRequest.of(
				page,
				size,
				org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.ASC, CREATED_AT)
		);
	}
	
}