package com.berkayb.soundconnect.modules.profile.VenueProfile.dto.response;

import java.util.UUID;

public record VenueProfileResponseDto(
		UUID id,
		UUID venueId,
		String venueName,
		String bio,
		UUID profilePictureMediaId,
		String instagramUrl,
		String youtubeUrl,
		String websiteUrl
		
) {
}