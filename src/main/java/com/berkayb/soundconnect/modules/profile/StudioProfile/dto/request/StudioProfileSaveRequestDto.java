package com.berkayb.soundconnect.modules.profile.StudioProfile.dto.request;

import java.util.Set;

public record StudioProfileSaveRequestDto(
		String name,
		String descpriction,
		String profilePicture,
		String adress,
		String phone,
		String website,
		Set<String> facilities,
		String instagramUrl,
		String youtubeUrl
		
) {
}