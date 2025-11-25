package com.berkayb.soundconnect.modules.social.like.service;

import com.berkayb.soundconnect.modules.social.comment.enums.EngagementTargetType;
import com.berkayb.soundconnect.modules.social.like.entity.Like;
import com.berkayb.soundconnect.modules.social.like.repository.LikeRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.support.UserEntityFinder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class LikeServiceImpl implements LikeService{
	
	private final LikeRepository likeRepository;
	private final UserEntityFinder userEntityFinder;
	
	
	@Override
	public void like(UUID userId, EngagementTargetType targetType, UUID targetId) {
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
		} catch (Exception e) {
			// duplicate riskine karsi
			log.warn("[LikeService] Potential race-condition prevented for user {} like on {}:{}",userId, targetType, targetId);
		}
		
		log.info("[LikeService] User {} likked {}:{}", userId, targetType, targetId);
	}
	
	
	@Override
	public void unlike(UUID userId, EngagementTargetType targetType, UUID targetId) {
		// Idempotent: zaten yoksa hata firlatmicaz
		
		boolean exists = likeRepository.existsByUserIdAndTargetTypeAndTargetId(userId, targetType, targetId);
		
		if (!exists) {
			log.debug("[LikeService] User {} has no like to remove on {}:{}", userId, targetType, targetId);
			return;
		}
		
		likeRepository.deleteByUserIdAndTargetTypeAndTargetId(userId, targetType, targetId);
		
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