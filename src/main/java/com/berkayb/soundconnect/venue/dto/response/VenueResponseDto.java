package com.berkayb.soundconnect.venue.dto.response;

import com.berkayb.soundconnect.venue.enums.VenueStatus;

import java.util.UUID;

public record VenueResponseDto(
		
		UUID id,
		String name,
		String address,
		String phone,
		String website,
		String description,
		String musicStartTime,
		
		String cityName,
		String districtName,
		String neighborhoodName,
		
		VenueStatus status,
		
		UUID ownerId,
		String ownerFullName

) {}