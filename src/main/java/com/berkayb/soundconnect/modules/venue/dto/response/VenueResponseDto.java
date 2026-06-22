package com.berkayb.soundconnect.modules.venue.dto.response;

import com.berkayb.soundconnect.modules.venue.enums.VenueStatus;

import java.util.Set;
import java.util.UUID;

public record VenueResponseDto(
		
		UUID id,
		String name,
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
		
		UUID ownerId,
		String ownerFullName,
		Set<String> activeMusicians

) {}