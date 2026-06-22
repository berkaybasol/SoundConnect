package com.berkayb.soundconnect.modules.promotion.dto.response;

import com.berkayb.soundconnect.modules.promotion.enums.PromotionPlacement;
import com.berkayb.soundconnect.modules.promotion.enums.PromotionStatus;
import com.berkayb.soundconnect.modules.promotion.enums.PromotionType;

import java.time.LocalDateTime;
import java.util.UUID;

public record PromotionResponseDto(
		UUID id,
		
		PromotionType type,
		
		PromotionPlacement placement,
		
		PromotionStatus status,
		
		String title,
		
		String description,
		
		UUID mediaAssetId,
		
		String mediaUrl,
		
		String redirectUrl,
		
		Integer priority,
		
		LocalDateTime startDate,
		
		LocalDateTime endDate,
		
		LocalDateTime createdAt,
		
		LocalDateTime updatedAt
) {
}