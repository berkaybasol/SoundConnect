package com.berkayb.soundconnect.modules.location.dto.request;

import jakarta.validation.constraints.NotBlank;

public record CityRequestDto(
		@NotBlank
		(message = "Şehir adı boş olamaz")
		String name
) {
}