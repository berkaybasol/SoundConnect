package com.berkayb.soundconnect.modules.comment.service;

import com.berkayb.soundconnect.modules.comment.dto.request.CommentCreateRequestDto;
import com.berkayb.soundconnect.modules.comment.abuse.CommentBurstGuard;
import com.berkayb.soundconnect.modules.comment.dto.response.CommentReplyResponseDto;
import com.berkayb.soundconnect.modules.comment.dto.response.CommentResponseDto;
import com.berkayb.soundconnect.modules.comment.entity.Comment;
import com.berkayb.soundconnect.modules.comment.mapper.CommentMapper;
import com.berkayb.soundconnect.modules.comment.repository.CommentRepository;
import com.berkayb.soundconnect.modules.like.repository.LikeRepository;
import com.berkayb.soundconnect.modules.comment.support.CommentEntityFinder;
import com.berkayb.soundconnect.modules.comment.support.CommentTargetAccessGuard;
import com.berkayb.soundconnect.modules.comment.support.CommentAuthorBatchResolver;
import com.berkayb.soundconnect.modules.comment.dto.support.UserSummaryDto;
import com.berkayb.soundconnect.modules.engagement.enums.EngagementTargetType;
import com.berkayb.soundconnect.modules.overthinking.entity.OverthinkingPost;
import com.berkayb.soundconnect.modules.overthinking.repository.OverthinkingPostRepository;
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
import org.springframework.transaction.annotation.Isolation;

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
	
	private static final int MAX_COMMENT_LENGTH = 500;
	private static final String DELETED_COMMENT_PLACEHOLDER = "[Bu yorum silinmiştir]";
	
	private final CommentTargetAccessGuard targetAccess;
	private final CommentRepository commentRepository;
	private final CommentMapper commentMapper;
	private final CommentEntityFinder commentEntityFinder;
	private final UserEntityFinder userEntityFinder;
	private final OverthinkingPostRepository overthinkingPostRepository;
	private final CommentAuthorBatchResolver authorResolver;
	private final CommentBurstGuard burstGuard;
	private final LikeRepository likes;
	
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
		if (request == null) throw new SoundConnectException(ErrorType.COMMENT_TEXT_INVALID);
		validateCommentText(request.text());
		
		targetAccess.requireReadable(targetType, targetId);
		
		User author = userEntityFinder.getUser(userId);
		
		Comment parent = null;
		if (request.parentCommentId() != null) {
			var lockedParent = commentRepository.lockComment(request.parentCommentId())
					.orElseThrow(() -> new SoundConnectException(ErrorType.COMMENT_NOT_FOUND));
			
			if (lockedParent.getDeleted()) {
				throw new SoundConnectException(ErrorType.COMMENT_PARENT_DELETED);
			}
			
			if (lockedParent.getParentId() != null) {
				throw new SoundConnectException(ErrorType.COMMENT_REPLY_DEPTH_NOT_ALLOWED);
			}
			
			if (!targetType.name().equals(lockedParent.getTargetType()) || !targetId.equals(lockedParent.getTargetId())) {
				throw new SoundConnectException(ErrorType.COMMENT_PARENT_TARGET_MISMATCH);
			}
			parent = commentRepository.getReferenceById(lockedParent.getId());
		}
		burstGuard.reserve(userId, targetType, targetId);
		
		Comment comment = Comment.builder()
		                         .user(author)
		                         .targetType(targetType)
		                         .targetId(targetId)
		                         .text(request.text().trim())
		                         .parentComment(parent)
		                         .deleted(false)
		                         .build();
		
		comment = commentRepository.save(comment);
		
		return mapToCommentResponse(comment, 0, userId, resolveAuthorContext(List.of(comment), userId),Map.of());
	}
	
	@Override
	public void deleteComment(UUID userId, UUID commentId) {
		var comment = commentRepository.lockComment(commentId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.COMMENT_NOT_FOUND));
		
		if (!comment.getUserId().equals(userId)) {
			throw new SoundConnectException(ErrorType.COMMENT_FORBIDDEN);
		}
		
		if (comment.getDeleted()) {
			log.debug("[CommentService] Comment {} already deleted", commentId);
			return;
		}
		
		commentRepository.softDelete(commentId);
		log.info("[CommentService] Comment {} soft deleted by user {}", commentId, userId);
	}
	
	@Override
	@Transactional(isolation = Isolation.REPEATABLE_READ)
	public Page<CommentResponseDto> getComments(
			UUID viewerId,
			EngagementTargetType targetType,
			UUID targetId,
			Pageable pageable
	) {
		Pageable safePageable = buildCommentPageable(pageable);
		targetAccess.requireReadable(targetType, targetId);
		
		Page<Comment> page = commentRepository.findByTargetTypeAndTargetIdAndParentCommentIsNull(
				targetType,
				targetId,
				safePageable
		);
		
		Map<UUID, Integer> replyCountMap = getReplyCountMap(page.getContent());
		CommentAuthorContext authorContext = resolveAuthorContext(page.getContent(), viewerId);
		var likeContext=loadLikes(page.getContent(),viewerId);
		
		return page.map(comment -> {
			int replyCount = replyCountMap.getOrDefault(comment.getId(), 0);
			return mapToCommentResponse(comment, replyCount, viewerId, authorContext,likeContext);
		});
	}
	
	@Override
	@Transactional(isolation = Isolation.REPEATABLE_READ)
	public Page<CommentReplyResponseDto> getReplies(UUID viewerId, UUID parentCommentId, Pageable pageable) {
		Comment parent = commentEntityFinder.getById(parentCommentId);
		if (parent.getParentComment() != null) throw new SoundConnectException(ErrorType.COMMENT_REPLY_DEPTH_NOT_ALLOWED);
		targetAccess.requireReadable(parent.getTargetType(), parent.getTargetId());
		
		Pageable safePageable = buildReplyPageable(pageable);
		
		Page<Comment> replies = commentRepository.findByParentComment(parent, safePageable);
		CommentAuthorContext authorContext = resolveAuthorContext(replies.getContent(), viewerId);
		var likeContext=loadLikes(replies.getContent(),viewerId);
		
		return replies.map(comment -> mapToReplyResponse(comment, viewerId, authorContext,likeContext));
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
			CommentAuthorContext authorContext,
			Map<UUID,LikeRepository.CommentLikes> likeContext
	) {
		boolean maskAuthor = shouldMaskCommentAuthor(comment, viewerId, authorContext.postsByTargetId());
		UserSummaryDto author = visibleAuthor(comment, maskAuthor, authorContext);
		CommentResponseDto dto = commentMapper.toResolvedComment(
				comment,
				replyCount,
				maskAuthor,
				author
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
		
		var state=likeContext.get(comment.getId());
		return new CommentResponseDto(dto.id(),dto.user(),dto.anonymousAuthor(),dto.text(),dto.deleted(),
				dto.parentCommentId(),dto.replyCount(),dto.createdAt(),state==null ? 0 : state.getLikeCount(),
				viewerId!=null && state!=null && state.getLikedByMe());
	}
	
	private CommentReplyResponseDto mapToReplyResponse(
			Comment comment,
			UUID viewerId,
			CommentAuthorContext authorContext,
			Map<UUID,LikeRepository.CommentLikes> likeContext
	) {
		boolean maskAuthor = shouldMaskCommentAuthor(comment, viewerId, authorContext.postsByTargetId());
		UserSummaryDto author = visibleAuthor(comment, maskAuthor, authorContext);
		CommentReplyResponseDto dto = commentMapper.toResolvedReply(
				comment,
				maskAuthor,
				author
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
		
		var state=likeContext.get(comment.getId());
		return new CommentReplyResponseDto(dto.id(),dto.user(),dto.anonymousAuthor(),dto.text(),dto.deleted(),
				dto.parentCommentId(),dto.createdAt(),state==null ? 0 : state.getLikeCount(),
				viewerId!=null && state!=null && state.getLikedByMe());
	}

	private Map<UUID,LikeRepository.CommentLikes> loadLikes(List<Comment> page,UUID viewerId) {
		var ids=page.stream().filter(comment -> !comment.isDeleted()).map(Comment::getId).toList();
		if(ids.isEmpty()) return Map.of();
		return likes.commentLikes(ids,viewerId).stream().collect(Collectors.toMap(LikeRepository.CommentLikes::getTargetId,row -> row));
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
		if (post == null) return true;
		if (!post.isAnonymous()) {
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

		Map<UUID, UserSummaryDto> authors = visibleAuthorIds.isEmpty()
				? Map.of()
				: authorResolver.resolve(visibleAuthorIds);
		return new CommentAuthorContext(postsByTargetId, authors);
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

	private UserSummaryDto visibleAuthor(
			Comment comment,
			boolean maskAuthor,
			CommentAuthorContext authorContext
	) {
		if (maskAuthor || comment.getUser() == null) return null;
		return authorContext.authors().get(comment.getUser().getId());
	}

	private record CommentAuthorContext(
			Map<UUID, OverthinkingPost> postsByTargetId,
			Map<UUID, UserSummaryDto> authors
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
		if (page > 1000) throw new SoundConnectException(ErrorType.INVALID_PARAMETER);
		int size = pageable != null ? Math.min(Math.max(pageable.getPageSize(), 1), MAX_PAGE_SIZE) : DEFAULT_PAGE_SIZE;
		
		return org.springframework.data.domain.PageRequest.of(
				page,
				size,
				org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, CREATED_AT, "id")
		);
	}
	
	private Pageable buildReplyPageable(Pageable pageable) {
		int page = pageable != null ? Math.max(pageable.getPageNumber(), 0) : DEFAULT_PAGE_NUMBER;
		if (page > 1000) throw new SoundConnectException(ErrorType.INVALID_PARAMETER);
		int size = pageable != null ? Math.min(Math.max(pageable.getPageSize(), 1), MAX_PAGE_SIZE) : DEFAULT_PAGE_SIZE;
		
		return org.springframework.data.domain.PageRequest.of(
				page,
				size,
				org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.ASC, CREATED_AT, "id")
		);
	}
}
