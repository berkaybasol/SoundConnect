package com.berkayb.soundconnect.modules.collab.dto.request;

import com.berkayb.soundconnect.modules.collab.enums.CollabCategory;
import com.berkayb.soundconnect.modules.collab.enums.CollabRole;
import jakarta.validation.constraints.*;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

public record CollabCreateRequestDto(
		
		@NotBlank
		@Size(max = 128)
		String title,
		
		@Size(max = 2048)
		String description,
		
		@NotNull
		CollabCategory category,
		
		@NotEmpty
		Set<CollabRole> targetRoles,
		
		@NotNull
		UUID cityId,
		
		@PositiveOrZero
		Integer price,
		
		@NotNull
		boolean daily,
		
		LocalDateTime expirationTime,
		
		@NotEmpty(message = "En az 1 slot tanimlanmalidir.")
		Set<RequiredSlotRequestDto> requiredSlots

) {
}