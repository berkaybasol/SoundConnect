package com.berkayb.soundconnect.modules.application.venueapplication.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record VenueApplicationCreateRequestDto(
		@NotBlank(message = "Venue name is required")
		@Size(max = 100, message = "Venue name can be at most 100 characters")
		String venueName,
		
		@NotBlank(message = "Venue address is required")
		@Size(max = 255, message = "Venue address can be at most 255 characters")
		String venueAddress,
		
		@NotBlank(message = "City ID is required")
		String cityId,
		
		@NotBlank(message = "District ID is required")
		String districtId,
		
		String neighborhoodId // Optional olabilir
) {}