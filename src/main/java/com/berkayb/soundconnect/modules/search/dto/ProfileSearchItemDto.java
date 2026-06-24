package com.berkayb.soundconnect.modules.search.dto;

import java.util.UUID;

public record ProfileSearchItemDto(
		String type,
		UUID targetId,
		UUID userId,
		String title,
		String subtitle,
		String imageUrl
) {
}
