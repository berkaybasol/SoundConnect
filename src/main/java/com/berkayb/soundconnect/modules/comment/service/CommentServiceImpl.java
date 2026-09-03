package com.berkayb.soundconnect.modules.comment.service;

import com.berkayb.soundconnect.modules.comment.dto.request.CommentCreateRequestDto;
import com.berkayb.soundconnect.modules.comment.dto.response.CommentReplyResponseDto;
import com.berkayb.soundconnect.modules.comment.dto.response.CommentResponseDto;
import com.berkayb.soundconnect.modules.comment.entity.Comment;
import com.berkayb.soundconnect.modules.comment.mapper.CommentMapper;
import com.berkayb.soundconnect.modules.comment.repository.CommentRepository;
import com.berkayb.soundconnect.modules.comment.support.CommentEntityFinder;
import com.berkayb.soundconnect.modules.engagement.enums.EngagementTargetType;
import com.berkayb.soundconnect.modules.engagement.service.EngagementTargetValidator;
import com.berkayb.soundconnect.modules.overthinking.entity.OverthinkingPost;
import com.berkayb.soundconnect.modules.overthinking.repository.OverthinkingPostRepository;
import com.berkayb.soundconnect.modules.profile.shared.identity.GhostListenerIdentity;
import com.berkayb.soundconnect.modules.profile.shared.identity.GhostListenerIdentityBatchResolver;
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

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class CommentServiceImpl implements CommentService {
	
	private static final int DEFAULT_PAGE_NUMBER = 0;
	private static final int DEFAULT_PAGE_SIZE = 10;
	private static final int MAX_PAGE_SIZE = 50;
	private static final String CREATED_AT = "createdAt";
	
	private static final int MAX_COMMENT_LENGTH = 2000;
	private static final String DELETED_COMMENT_PLACEHOLDER = "[Bu yorum silinmiştir]";
	
	private final EngagementTargetValidator engagementTargetValidator;
	private final CommentRepository commentRepository;
	private final CommentMapper commentMapper;
	private final CommentEntityFinder commentEntityFinder;
	private final UserEntityFinder userEntityFinder;
	private final OverthinkingPostRepository overthinkingPostRepository;
	private final GhostListenerIdentityBatchResolver ghostIdentityBatchResolver;
	
	@Override
	@Transactional(readOnly = true)
	public Map<UUID, Long> countCommentsByTargets(EngagementTargetType targetType, Collection<UUID> targetIds) {
		if (targetIds == null || targetIds.isEmpty()) {
			return Collections.emptyMap();
		}
		
		return commentRepository.countByTargetTypeAndTargetIdIn(targetType, targetIds)
		                        .stream()
		                        .collect(Collectors.toMap(
				                        CommentRepository.TargetCountProjection::getTargetId,
				                        CommentRepository.TargetCountProjection::getCount
		                        ));
	}
	
	@Override
	public CommentResponseDto createComment(
			UUID userId,
			EngagementTargetType targetType,
			UUID targetId,
			CommentCreateRequestDto request
	) {
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
		
		GhostListenerIdentity ghostIdentity = ghostIdentityBatchResolver
				.resolve(Set.of(author.getId()))
				.get(author.getId());
		return commentMapper.toCommentResponseDto(comment, 0, false, ghostIdentity);
	}
	
	@Override
	public void deleteComment(UUID userId, UUID commentId) {
		Comment comment = commentEntityFinder.getById(commentId);
		
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
	@Transactional
	public Page<CommentResponseDto> getComments(
			UUID viewerId,
			EngagementTargetType targetType,
			UUID targetId,
			Pageable pageable
	) {
		Pageable safePageable = buildCommentPageable(pageable);
		
		Page<Comment> page = commentRepository.findByTargetTypeAndTargetIdAndParentCommentIsNull(
				targetType,
				targetId,
				safePageable
		);
		
		Map<UUID, Integer> replyCountMap = getReplyCountMap(page.getContent());
		CommentAuthorContext authorContext = resolveAuthorContext(page.getContent(), viewerId);
		
		return page.map(comment -> {
			int replyCount = replyCountMap.getOrDefault(comment.getId(), 0);
			return mapToCommentResponse(comment, replyCount, viewerId, authorContext);
		});
	}
	
	@Override
	@Transactional
	public Page<CommentReplyResponseDto> getReplies(UUID viewerId, UUID parentCommentId, Pageable pageable) {
		Comment parent = commentEntityFinder.getById(parentCommentId);
		
		Pageable safePageable = buildReplyPageable(pageable);
		
		Page<Comment> replies = commentRepository.findByParentComment(parent, safePageable);
		CommentAuthorContext authorContext = resolveAuthorContext(replies.getContent(), viewerId);
		
		return replies.map(comment -> mapToReplyResponse(comment, viewerId, authorContext));
	}
	
	@Override
	@Transactional(readOnly = true)
	public long countComments(EngagementTargetType targetType, UUID targetId) {
		return commentRepository.countByTargetTypeAndTargetId(targetType, targetId);
	}
	
	private void validateCommentText(String text) {
		if (text == null || text.trim().isEmpty()) {
			throw new SoundConnectException(ErrorType.COMMENT_TEXT_INVALID);
		}
		
		String trimmed = text.trim();
		if (trimmed.length() > MAX_COMMENT_LENGTH) {
			throw new SoundConnectException(ErrorType.COMMENT_TEXT_INVALID);
		}
	}
	
	private CommentResponseDto mapToCommentResponse(
			Comment comment,
			int replyCount,
			UUID viewerId,
			CommentAuthorContext authorContext
	) {
		boolean maskAuthor = shouldMaskCommentAuthor(comment, viewerId, authorContext.postsByTargetId());
		GhostListenerIdentity ghostIdentity = visibleGhostIdentity(comment, maskAuthor, authorContext);
		CommentResponseDto dto = commentMapper.toCommentResponseDto(
				comment,
				replyCount,
				maskAuthor,
				ghostIdentity
		);
		
		if (comment.isDeleted()) {
			return new CommentResponseDto(
					dto.id(),
					dto.user(),
					dto.anonymousAuthor(),
					DELETED_COMMENT_PLACEHOLDER,
					true,
					dto.parentCommentId(),
					dto.replyCount(),
					dto.createdAt()
			);
		}
		
		return dto;
	}
	
	private CommentReplyResponseDto mapToReplyResponse(
			Comment comment,
			UUID viewerId,
			CommentAuthorContext authorContext
	) {
		boolean maskAuthor = shouldMaskCommentAuthor(comment, viewerId, authorContext.postsByTargetId());
		GhostListenerIdentity ghostIdentity = visibleGhostIdentity(comment, maskAuthor, authorContext);
		CommentReplyResponseDto dto = commentMapper.toCommentReplyResponseDto(
				comment,
				maskAuthor,
				ghostIdentity
		);
		
		if (comment.isDeleted()) {
			return new CommentReplyResponseDto(
					dto.id(),
					dto.user(),
					dto.anonymousAuthor(),
					DELETED_COMMENT_PLACEHOLDER,
					true,
					dto.parentCommentId(),
					dto.createdAt()
			);
		}
		
		return dto;
	}
	
	private boolean shouldMaskCommentAuthor(
			Comment comment,
			UUID viewerId,
			Map<UUID, OverthinkingPost> postsByTargetId
	) {
		if (comment.getTargetType() != EngagementTargetType.OVERTHINKING) {
			return false;
		}
		
		OverthinkingPost post = postsByTargetId.get(comment.getTargetId());
		if (post == null || !post.isAnonymous()) {
			return false;
		}
		if (post.getAuthor() == null || post.getAuthor().getId() == null
				|| comment.getUser() == null || comment.getUser().getId() == null) {
			// Corrupt legacy rows must fail closed instead of exposing an author whose
			// anonymity relationship cannot be established safely.
			return true;
		}
		
		boolean commentAuthorIsPostAuthor = post.getAuthor().getId().equals(comment.getUser().getId());
		if (!commentAuthorIsPostAuthor) {
			return false;
		}
		
		return viewerId == null || !post.getAuthor().getId().equals(viewerId);
	}

	private CommentAuthorContext resolveAuthorContext(List<Comment> comments, UUID viewerId) {
		if (comments == null || comments.isEmpty()) {
			return new CommentAuthorContext(Map.of(), Map.of());
		}

		Map<UUID, OverthinkingPost> postsByTargetId = loadOverthinkingPosts(comments);
		LinkedHashSet<UUID> visibleAuthorIds = new LinkedHashSet<>();
		for (Comment comment : comments) {
			if (comment == null || comment.getUser() == null || comment.getUser().getId() == null) continue;
			if (!shouldMaskCommentAuthor(comment, viewerId, postsByTargetId)) {
				visibleAuthorIds.add(comment.getUser().getId());
			}
		}

		Map<UUID, GhostListenerIdentity> ghostIdentities = visibleAuthorIds.isEmpty()
				? Map.of()
				: ghostIdentityBatchResolver.resolve(visibleAuthorIds);
		return new CommentAuthorContext(postsByTargetId, ghostIdentities);
	}

	private Map<UUID, OverthinkingPost> loadOverthinkingPosts(List<Comment> comments) {
		LinkedHashSet<UUID> targetIds = comments.stream()
				.filter(Objects::nonNull)
				.filter(comment -> comment.getTargetType() == EngagementTargetType.OVERTHINKING)
				.map(Comment::getTargetId)
				.filter(Objects::nonNull)
				.collect(Collectors.toCollection(LinkedHashSet::new));
		if (targetIds.isEmpty()) return Map.of();

		return overthinkingPostRepository.findAllById(targetIds).stream()
				.filter(Objects::nonNull)
				.filter(post -> post.getId() != null)
				.collect(Collectors.toMap(
						OverthinkingPost::getId,
						post -> post,
						(first, ignored) -> first,
						LinkedHashMap::new
				));
	}

	private GhostListenerIdentity visibleGhostIdentity(
			Comment comment,
			boolean maskAuthor,
			CommentAuthorContext authorContext
	) {
		if (maskAuthor || comment.getUser() == null) return null;
		return authorContext.ghostIdentities().get(comment.getUser().getId());
	}

	private record CommentAuthorContext(
			Map<UUID, OverthinkingPost> postsByTargetId,
			Map<UUID, GhostListenerIdentity> ghostIdentities
	) {
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
