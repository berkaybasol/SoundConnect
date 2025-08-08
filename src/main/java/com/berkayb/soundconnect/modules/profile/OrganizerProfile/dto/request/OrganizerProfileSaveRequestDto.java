package com.berkayb.soundconnect.modules.profile.OrganizerProfile.dto.request;

public record OrganizerProfileSaveRequestDto(
		String name,
		String description,
		String profilePicture,
		String address,
		String phone,
		String instagramUrl,
		String youtubeUrl
) {
}