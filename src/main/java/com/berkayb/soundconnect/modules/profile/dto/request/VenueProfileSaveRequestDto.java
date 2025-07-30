package com.berkayb.soundconnect.modules.profile.dto.request;


import java.util.UUID;

public record VenueProfileSaveRequestDto(
		String bio,
		String profilePicture,
		String instagramUrl,
		String youtubeUrl,
		String websiteUrl
) {
}