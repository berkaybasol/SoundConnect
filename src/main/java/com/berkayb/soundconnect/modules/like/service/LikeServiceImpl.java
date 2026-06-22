package com.berkayb.soundconnect.modules.like.service;

import com.berkayb.soundconnect.modules.engagement.enums.EngagementTargetType;
import com.berkayb.soundconnect.modules.engagement.service.EngagementTargetValidator;
import com.berkayb.soundconnect.modules.like.entity.Like;
import com.berkayb.soundconnect.modules.like.repository.LikeRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.support.UserEntityFinder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
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
	private final UserEntityFinder userEntityFinder;
	private final EngagementTargetValidator engagementTargetValidator;
	
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
		// target dogrula
		engagementTargetValidator.validateExists(targetType, targetId);
		
		// kullaniciyi dogrula
		User user = userEntityFinder.getUser(userId);
		
		// kullanici daha once begenmis mi?
		boolean exists = likeRepository.existsByUserIdAndTargetTypeAndTargetId(userId, targetType, targetId);
		
		if (exists) {
			// idempotent davranis
			log.debug("[LikeService] User {} already likked {}:{}", userId, targetType, targetId);
			return;
		}
		
		// like olusturuluyor
		Like like = Like.builder()
				.user(user)
				.targetType(targetType)
				.targetId(targetId)
				.build();
		
		try {
			likeRepository.save(like);
		} catch (DataIntegrityViolationException e) {
			// duplicate riskine karsi
			log.warn("[LikeService] Potential race-condition prevented for user {} like on {}:{}",userId, targetType, targetId);
		}
		
		log.info("[LikeService] User {} likked {}:{}", userId, targetType, targetId);
	}
	
	
	@Override
	public void unlike(UUID userId, EngagementTargetType targetType, UUID targetId) {
		engagementTargetValidator.validateExists(targetType, targetId);
		
		long deletedCount = likeRepository.deleteByUserIdAndTargetTypeAndTargetId(userId, targetType, targetId);
		
		if (deletedCount == 0) {
			log.debug("[LikeService] User {} had no like to remove on {}:{}", userId, targetType, targetId);
			return;
		}
		
		log.info("[LikeService] User {} unliked {}:{}", userId, targetType, targetId);
	}
	
	@Override
	@Transactional(readOnly = true)
	public boolean isLiked(UUID userId, EngagementTargetType targetType, UUID targetId) {
		return likeRepository.existsByUserIdAndTargetTypeAndTargetId(userId, targetType, targetId);
	}
	
	@Override
	@Transactional(readOnly = true)
	public long countLikes(EngagementTargetType targetType, UUID targetId) {
		return likeRepository.countByTargetTypeAndTargetId(targetType, targetId);
	}
}