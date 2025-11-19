package com.berkayb.soundconnect.modules.collab.dto.request;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CollabUnfillSlotRequestDto(
		@NotNull(message = "Instrument ID zorunludur.")
		UUID instrumentId
) {
}