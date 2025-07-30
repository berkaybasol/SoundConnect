package com.berkayb.soundconnect.modules.profile.dto.response;

import java.util.UUID;

public record VenueProfileResponseDto(
		UUID id,
		UUID venueId,
		String venueName,
		String bio,
		String profilePicture,
		String instagramUrl,
		String youtubeUrl,
		String websiteUrl
		
) {
}