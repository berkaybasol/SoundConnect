package com.berkayb.soundconnect.modules.profile.OrganizerProfile.dto.request;

import java.util.UUID;

public record OrganizerProfileSaveRequestDto(
		String name,
		String description,
		UUID profilePicture,
		String address,
		String phone,
		String instagramUrl,
		String youtubeUrl
) {
}