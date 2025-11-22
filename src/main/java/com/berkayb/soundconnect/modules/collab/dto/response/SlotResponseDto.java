package com.berkayb.soundconnect.modules.collab.dto.response;

import java.util.UUID;

public record SlotResponseDto(
		UUID instrumentID,
		String instrumentName,
		int requiredCount,
		int filledCount,
		boolean hasOpenSlot
) {
}