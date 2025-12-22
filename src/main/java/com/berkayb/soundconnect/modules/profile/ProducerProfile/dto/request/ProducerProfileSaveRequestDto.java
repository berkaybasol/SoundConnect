package com.berkayb.soundconnect.modules.profile.ProducerProfile.dto.request;

import java.util.UUID;

public record ProducerProfileSaveRequestDto(
		String name,
		String description,
		UUID profilePicture,
		String address,
		String phone,
		String website,
		String instagramUrl,
		String youtubeUrl
) {
}