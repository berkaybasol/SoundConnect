package com.berkayb.soundconnect.modules.collab.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record RequiredSlotRequestDto(
		@NotNull(message = "Instrument ID zorunludur.")
		UUID instrumentId,
		
		@Min(value = 1, message = "requiredCount en az 1 olmalidir.")
		int requiredCount
) {
}