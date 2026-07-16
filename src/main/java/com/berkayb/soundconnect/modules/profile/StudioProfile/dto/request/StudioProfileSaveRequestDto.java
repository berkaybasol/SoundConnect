package com.berkayb.soundconnect.modules.profile.StudioProfile.dto.request;

import com.fasterxml.jackson.annotation.JsonAlias;

import java.util.Set;
import java.util.UUID;

public record StudioProfileSaveRequestDto(
		String name,
		@JsonAlias("description")
		String descpriction,
		UUID profilePicture,
		@JsonAlias("address")
		String adress,
		String phone,
		String website,
		Set<String> facilities,
		String instagramUrl,
		String youtubeUrl
		
) {
}
