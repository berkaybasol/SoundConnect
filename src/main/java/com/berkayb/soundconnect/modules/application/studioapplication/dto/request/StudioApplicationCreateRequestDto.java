package com.berkayb.soundconnect.modules.application.studioapplication.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record StudioApplicationCreateRequestDto(
		@NotBlank(message = "Studyo adi zorunludur")
		@Size(max = 100, message = "Studyo adi en fazla 100 karakter olabilir")
		String studioName,
		@NotBlank(message = "Studyo adresi zorunludur")
		@Size(max = 255, message = "Studyo adresi en fazla 255 karakter olabilir")
		String studioAddress,
		@NotBlank(message = "Telefon numarasi zorunludur")
		@Size(min = 10, max = 32, message = "Telefon numarasi gecersiz")
		@Pattern(
				regexp = "^(?:\\+)?[0-9() .-]+$",
				message = "Telefon numarasi gecersiz"
		)
		String phone,
		@NotBlank(message = "Sehir secilmelidir") String cityId,
		@NotBlank(message = "Ilce secilmelidir") String districtId,
		@NotBlank(message = "Mahalle secilmelidir") String neighborhoodId
) {}
