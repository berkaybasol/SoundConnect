package com.berkayb.soundconnect.modules.setlistcreator.dto.response;

import java.util.List;
import java.util.UUID;

public record SetlistResponseDto(
		UUID id,
		String name,
		List<SetlistResponseDto> sets
) {
}