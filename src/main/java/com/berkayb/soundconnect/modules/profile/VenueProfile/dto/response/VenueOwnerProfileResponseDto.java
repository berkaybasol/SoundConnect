package com.berkayb.soundconnect.modules.profile.VenueProfile.dto.response;

import com.berkayb.soundconnect.modules.venue.enums.VenueStatus;

import java.util.List;
import java.util.UUID;

public record VenueOwnerProfileResponseDto(
		UUID venueProfileId,
		UUID venueId,
		UUID ownerUserId,
		
		String venueName,
		String bio,
		
		UUID profilePictureMediaId,
		String profilePictureUrl,
		
		String instagramUrl,
		String youtubeUrl,
		String websiteUrl,
		
		String address,
		String phone,
		String website,
		String description,
		String musicStartTime,
		
		UUID cityId,
		String cityName,
		UUID districtId,
		String districtName,
		UUID neighborhoodId,
		String neighborhoodName,
		
		VenueStatus status,
		
		List<VenueActiveMusicianDto> activeMusicians,
		List<VenueActiveBandDto> activeBands,
		List<VenueEventSummaryDto> weeklyEvents
		
) {
}