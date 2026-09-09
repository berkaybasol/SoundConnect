package com.berkayb.soundconnect.modules.like.service;

import com.berkayb.soundconnect.modules.engagement.enums.EngagementTargetType;
import com.berkayb.soundconnect.modules.engagement.service.EngagementTargetValidator;
import com.berkayb.soundconnect.modules.engagement.service.MediaEngagementNotificationService;
import com.berkayb.soundconnect.modules.like.dto.CommentLikeState;
import com.berkayb.soundconnect.modules.like.repository.LikeRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class LikeServiceImpl implements LikeService{
	
	private final LikeRepository likeRepository;
	private final EngagementTargetValidator engagementTargetValidator;
	private final CommentLikeAccessGuard commentAccess;
	private final MediaEngagementNotificationService notifications;

	@Override
	public CommentLikeState setCommentLike(UUID userId,UUID commentId,boolean liked) {
		validateMutation(userId,EngagementTargetType.COMMENT,commentId);
		if(liked) likeRepository.insertIfAbsent(UUID.randomUUID(),userId,"COMMENT",commentId);
		else likeRepository.deleteDesiredLike(userId,"COMMENT",commentId);
		return commentState(userId,commentId);
	}

	@Override
	public CommentLikeState readCommentLike(UUID userId,UUID commentId) {
		requireActiveActor(userId);
		commentAccess.requireLikeable(commentId,false);
		return commentState(userId,commentId);
	}

	private CommentLikeState commentState(UUID userId,UUID commentId) {
		return likeRepository.commentLikes(List.of(commentId),userId).stream().findFirst()
				.map(row -> new CommentLikeState(row.getLikeCount(),row.getLikedByMe()))
				.orElse(new CommentLikeState(0,false));
	}
	
	@Override
	@Transactional(readOnly = true)
	public Map<UUID, Long> countLikesByTargets(EngagementTargetType targetType, Collection<UUID> targetIds) {
		if (targetIds == null || targetIds.isEmpty()) {
			return Collections.emptyMap();
		}
		
		return likeRepository.countByTargetTypeAndTargetIdIn(targetType, targetIds)
		                     .stream()
		                     .collect(Collectors.toMap(
				                     LikeRepository.TargetCountProjection::getTargetId,
				                     LikeRepository.TargetCountProjection::getCount
		                     ));
	}
	
	@Override
	@Transactional(readOnly = true)
	public Set<UUID> findLikedTargetIds(UUID userId, EngagementTargetType targetType, Collection<UUID> targetIds) {
		if (userId == null || targetIds == null || targetIds.isEmpty()) {
			return Collections.emptySet();
		}
		
		return likeRepository.findLikedTargetIds(userId, targetType, targetIds);
	}
	
	
	@Override
	public void like(UUID userId, EngagementTargetType targetType, UUID targetId) {
		validateMutation(userId,targetType,targetId);
		if (likeRepository.insertIfAbsent(UUID.randomUUID(),userId,targetType.name(),targetId) > 0) {
			notifications.liked(userId, targetType, targetId);
		}
	}
	
	
	@Override
	public void unlike(UUID userId, EngagementTargetType targetType, UUID targetId) {
		validateMutation(userId,targetType,targetId);
		likeRepository.deleteDesiredLike(userId,targetType.name(),targetId);
	}
	
	@Override
	@Transactional
	public boolean isLiked(UUID userId, EngagementTargetType targetType, UUID targetId) {
		requireActiveActor(userId);
		validateRead(targetType, targetId);
		return likeRepository.existsByUserIdAndTargetTypeAndTargetId(userId, targetType, targetId);
	}
	
	@Override
	@Transactional
	public long countLikes(EngagementTargetType targetType, UUID targetId) {
		validateRead(targetType, targetId);
		return likeRepository.countByTargetTypeAndTargetId(targetType, targetId);
	}

	private void validateMutation(UUID userId,EngagementTargetType type,UUID id) {
		if(userId==null) throw new SoundConnectException(ErrorType.UNAUTHORIZED);
		if(type==null || id==null) throw new SoundConnectException(ErrorType.INVALID_PARAMETER);
		requireActiveActor(userId);
		if(type==EngagementTargetType.COMMENT) {
			commentAccess.requireLikeable(id,true);
		} else {
			engagementTargetValidator.validateExists(type,id);
		}
	}

	private void validateRead(EngagementTargetType type, UUID id) {
		if(type==null || id==null) throw new SoundConnectException(ErrorType.INVALID_PARAMETER);
		if(type==EngagementTargetType.COMMENT) commentAccess.requireLikeable(id,false);
		else engagementTargetValidator.validateExists(type,id);
	}

	private void requireActiveActor(UUID userId) {
		if(userId==null || likeRepository.lockActiveActor(userId).isEmpty()) throw new SoundConnectException(ErrorType.UNAUTHORIZED);
	}
}
