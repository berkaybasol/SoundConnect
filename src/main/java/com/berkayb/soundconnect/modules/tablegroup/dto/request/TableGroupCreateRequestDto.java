package com.berkayb.soundconnect.modules.tablegroup.dto.request;

import jakarta.validation.constraints.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Masa olusturma istegi icin kullanilan dto
 */
public record TableGroupCreateRequestDto(
		// Soundconnect'e kayitli mekan icin dropdown olcak
		UUID venueId,
		// Soundconnect'te kaydi olmayan el ile girilecek mekan adi
		@Size(max = 64, message = "Venue adı en fazla 64 karakter olabilir")
		String venueName,
		
		// toplam masa sayisi 2-6 belirledik
		@Min(value = 2, message = "min 2 kisi")
		@Max(value = 6, message = "max 6 kisi")
		int maxPersonCount,
		
		// Cinsiyet tercihleri
		@NotNull
		@Size (min = 2, max = 6, message = "Cinsiyet listesi masa kapasitesiyle uyumlu olmali")
		List<@Pattern(regexp = "FEMALE|MALE|OTHER") String> genderPrefs,
		
		// yas araligi
		@Min(19) @Max(99)
		int ageMin,
		
		@Min(19) @Max(99)
		int ageMax,
		
		// Masa su saate kadar aktif
		@NotNull
		LocalDateTime expiresAt,
		
		@NotNull
		UUID cityId,
		UUID districtId,
		UUID neighborhoodId
		
) {
}