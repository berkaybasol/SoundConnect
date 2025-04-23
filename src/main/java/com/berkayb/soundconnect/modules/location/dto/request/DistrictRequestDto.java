package com.berkayb.soundconnect.modules.location.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record DistrictRequestDto(
		
		@NotBlank(message = "District name cannot be blank")
		String name,
		
		@NotNull(message = "City ID must not be null")
		UUID cityId

) {}