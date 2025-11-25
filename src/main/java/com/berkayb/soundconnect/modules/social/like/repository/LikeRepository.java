package com.berkayb.soundconnect.modules.social.like.repository;

import com.berkayb.soundconnect.modules.social.comment.enums.EngagementTargetType;
import com.berkayb.soundconnect.modules.social.like.entity.Like;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface LikeRepository extends JpaRepository<Like, UUID> {
	
	// kullanici bu icerigi begenmis mi?
	boolean existsByUserIdAndTargetTypeAndTargetId(UUID userId, EngagementTargetType targetType, UUID targetId);
	
	// begeniyi kaldir(idempotent)
	void deleteByUserIdAndTargetTypeAndTargetId(UUID userId, EngagementTargetType targetType, UUID targetId);
	
	// icerigin toplam begeni sayisi
	long countByTargetTypeAndTargetId(EngagementTargetType targetType, UUID targetId);
}