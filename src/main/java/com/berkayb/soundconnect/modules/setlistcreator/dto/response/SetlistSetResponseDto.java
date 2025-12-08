package com.berkayb.soundconnect.modules.setlistcreator.dto.response;

import java.util.UUID;

public record SetlistSetResponseDto(
		UUID id,
		String title,
		String duration,
		Integer orderNumber,
		List<SetlistItemResponseDto> items
) {
}