package com.berkayb.soundconnect.venue.dto.request;

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
		UUID neighboorId,
		
		@NotNull
		UUID ownerId,
		
		String phone,
		
		String website,
		
		String description,
		
		String musicStartTime
) {
}