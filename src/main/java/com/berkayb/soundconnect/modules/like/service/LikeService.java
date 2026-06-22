package com.berkayb.soundconnect.modules.like.service;

import com.berkayb.soundconnect.modules.engagement.enums.EngagementTargetType;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public interface LikeService {
	
	// begen (idempotent)
	void like (UUID userId, EngagementTargetType targetType, UUID targetId);
	
	// begeni kaldir(idempotent)
	void unlike(UUID userId, EngagementTargetType targetType, UUID targetId);
	
	// kullanici bu icerigi begenmis mi?
	boolean isLiked(UUID userId, EngagementTargetType targetType, UUID targetId);
	
	// icerigin toplam begeni sayisi
	long countLikes(EngagementTargetType targetType, UUID targetId);
	
	Map<UUID, Long> countLikesByTargets(EngagementTargetType targetType, Collection<UUID> targetIds);
	
	Set<UUID> findLikedTargetIds(UUID userId, EngagementTargetType targetType, Collection<UUID> targetIds);
	
}