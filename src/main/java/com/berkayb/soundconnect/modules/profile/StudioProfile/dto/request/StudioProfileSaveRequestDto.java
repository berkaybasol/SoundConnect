package com.berkayb.soundconnect.modules.profile.StudioProfile.dto.request;

import java.util.Set;
import java.util.UUID;

public record StudioProfileSaveRequestDto(
		String name,
		String descpriction,
		UUID profilePicture,
		String adress,
		String phone,
		String website,
		Set<String> facilities,
		String instagramUrl,
		String youtubeUrl
		
) {
}