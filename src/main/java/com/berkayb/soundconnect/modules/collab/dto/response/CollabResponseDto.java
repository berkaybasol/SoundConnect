package com.berkayb.soundconnect.modules.collab.dto.response;

import com.berkayb.soundconnect.modules.collab.enums.CollabCategory;
import com.berkayb.soundconnect.modules.collab.enums.CollabRole;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

// FE tarafina donulen ilan modeli.
public record CollabResponseDto(
		UUID id,
		
		UUID ownerId,
		
		CollabRole ownerRole,
		
		Set<CollabRole> targetRoles,
		
		CollabCategory category,
		
		String title,
		
		String description,
		
		Integer price,
		
		boolean daily,
		
		LocalDateTime expirationTime,
		
		UUID cityId,
		String cityName,
		
		Set<UUID> filledInstrumentIds,
		Set<UUID> requiredInstrumentIds,
		
		
		boolean hasOpenSlots, // open slot bilgisi required - filled > 0 mi? FE filtreleme ve badgelerde kullanisli
		
		// UI FRIENDLY FIELDS
		boolean isOwner,
		boolean isTarget,
		int slotCount
) {
}