package com.berkayb.soundconnect.location.dto.request;

import jakarta.validation.constraints.NotBlank;

public record CityRequestDto(
		@NotBlank
		(message = "Şehir adı boş olamaz")
		String name
) {
}