package com.berkayb.soundconnect.modules.social.like.service;

import com.berkayb.soundconnect.modules.social.comment.enums.EngagementTargetType;

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
}