package com.berkayb.soundconnect.modules.profile.StudioProfile.dto.response;

import java.util.Set;
import java.util.UUID;

public record StudioProfileResponseDto(
		UUID id,
		UUID userId,
		String name,
		String description,
		UUID profilePictureMediaId,
		String profilePictureUrl,
		String adress,
		String phone,
		String website,
		Set<String> facilities,
		String instagramUrl,
		String youtubeUrl
) {
}
