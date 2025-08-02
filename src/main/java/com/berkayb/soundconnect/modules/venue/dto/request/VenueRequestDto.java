package com.berkayb.soundconnect.modules.venue.dto.request;

import com.berkayb.soundconnect.modules.profile.VenueProfile.dto.request.VenueProfileSaveRequestDto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record VenueRequestDto(
		@NotBlank
		String name,
		
		@NotBlank
		String address,
		
		@NotNull
		UUID cityId,
		
		@NotNull
		UUID districtId,
		
		@NotNull
		UUID neighborhoodId,
		
		@NotNull
		UUID ownerId,
		
		VenueProfileSaveRequestDto profile,
		
		String phone,
		
		String website,
		
		String description,
		
		String musicStartTime
		
) {
}