package com.berkayb.soundconnect.modules.collab.dto.request;

import com.berkayb.soundconnect.modules.collab.enums.CollabCategory;
import com.berkayb.soundconnect.modules.collab.enums.CollabRole;
import jakarta.validation.constraints.*;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

public record CollabUpdateRequestDto(
		
		@NotBlank
		@Size(max = 128)
		String title,
		
		@Size(max = 2048)
		String description,
		
		@NotNull
		CollabCategory category,
		
		@NotEmpty
		Set<CollabRole> targetRoles,
		
		Set<UUID> requiredInstrumentIds,
		
		@NotNull
		UUID cityId,
		
		@PositiveOrZero
		Integer price,
		
		@NotNull
		boolean daily,
		
		LocalDateTime expirationTime
		
) {
}