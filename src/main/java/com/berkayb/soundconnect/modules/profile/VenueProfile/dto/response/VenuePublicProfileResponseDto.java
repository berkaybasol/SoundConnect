package com.berkayb.soundconnect.modules.profile.VenueProfile.dto.response;

import java.util.List;
import java.util.UUID;

public record VenuePublicProfileResponseDto(
		UUID venueProfileId,
		UUID venueId,
		UUID ownerUserId,
		
		String venueName,
		String bio,
		
		String profilePictureUrl,
		
		String instagramUrl,
		String youtubeUrl,
		String websiteUrl,
		
		String address,
		String phone,
		String website,
		String description,
		String musicStartTime,
		
		String cityName,
		String districtName,
		String neighborhoodName,
		
		List<VenueActiveMusicianDto> activeMusicians,
		List<VenueEventSummaryDto> weeklyEvents
) {
}