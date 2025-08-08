package com.berkayb.soundconnect.modules.profile.ProducerProfile.dto.response;

import java.util.UUID;

public record ProducerProfileResponseDto(
		UUID id,
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