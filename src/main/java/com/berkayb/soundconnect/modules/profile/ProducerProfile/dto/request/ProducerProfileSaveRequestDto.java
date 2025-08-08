package com.berkayb.soundconnect.modules.profile.ProducerProfile.dto.request;

public record ProducerProfileSaveRequestDto(
		String name,
		String description,
		String profilePicture,
		String address,
		String phone,
		String website,
		String instagramUrl,
		String youtubeUrl
) {
}