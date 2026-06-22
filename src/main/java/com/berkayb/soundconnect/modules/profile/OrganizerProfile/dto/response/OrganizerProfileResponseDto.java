package com.berkayb.soundconnect.modules.profile.OrganizerProfile.dto.response;

import java.util.UUID;

public record OrganizerProfileResponseDto(
		UUID id,
		String name,
		String description,
		UUID profilePictureMediaId,
		String address,
		String phone,
		String instagramUrl,
		String youtubeUrl
) {
}