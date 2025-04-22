package com.berkayb.soundconnect.location.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record NeighborhoodRequestDto(
		@NotBlank(message = "Neighborhood name cannot be blank")
		String name,
		@NotNull(message = "District ID must not be null")
		UUID districtId
){}