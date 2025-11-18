package com.berkayb.soundconnect.modules.collab.dto.request;

import com.berkayb.soundconnect.modules.collab.enums.CollabCategory;
import com.berkayb.soundconnect.modules.collab.enums.CollabRole;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

/**
 * Collab listeleme endpoint'i icin filtre DTO'su
 * Bu dto hem JPA Specification hem de ileride kuracagimiz Elasticsearch query'leri icin ayni contracti saglar.
 */
public record CollabFilterRequestDto(
		UUID cityId,
		
		CollabCategory category,
		
		CollabRole ownerRole,
		
		Set<CollabRole> targetRoles,
		
		UUID requiredInstrumentId,
		
		boolean daily,
		
		boolean hasOpenSlots,
		
		LocalDateTime createdAfter,
		LocalDateTime createdBefore
		
		
) {
}