package com.berkayb.soundconnect.modules.tablegroup.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Masa olusturma istegi icin kullanilan dto
 */
public record TableGroupCreateRequestDto(
		// Optional registered venue selection. Mutually exclusive with venueName.
		UUID venueId,
		// Optional custom venue name. Both venue fields may be absent.
		@Size(max = 64, message = "Venue adı en fazla 64 karakter olabilir")
		String venueName,

		// Service normalization owns the Unicode-aware trim and 280-code-point
		// bound so supplementary characters are not double-counted as UTF-16 units.
		@NotBlank(message = "Masa açıklaması zorunludur")
		String description,
		
		// toplam masa sayisi 2-6 belirledik
		@Min(value = 2, message = "min 2 kisi")
		@Max(value = 6, message = "max 6 kisi")
		int maxPersonCount,
		
		// Cinsiyet tercihleri
		@NotNull
		@Size (min = 2, max = 6, message = "Cinsiyet listesi masa kapasitesiyle uyumlu olmali")
		List<@NotNull @Pattern(regexp = "FEMALE|MALE|OTHER") String> genderPrefs,
		
		// yas araligi
		@Min(19) @Max(99)
		int ageMin,
		
		@Min(19) @Max(99)
		int ageMax,
		
		// Kullanıcının seçtiği gerçek buluşma saati. İstek yalnız string biçimli,
		// offset-aware RFC3339 meetingAt kabul eder; teknik kapanış zamanı sunucudur.
		@NotNull
		@JsonDeserialize(using = OffsetAwareInstantDeserializer.class)
		Instant meetingAt,
		
		@NotNull
		UUID cityId,
		UUID districtId,
		UUID neighborhoodId
) {
	/**
	 * Create is deliberately closed to unknown fields so the retired
	 * {@code expiresAt} alias and request typos cannot be silently ignored by a
	 * globally lenient Jackson configuration.
	 */
	@JsonAnySetter
	public void rejectUnknownProperty(String fieldName, JsonNode ignoredValue) {
		throw new IllegalArgumentException("Unknown TableGroup create field: " + fieldName);
	}
}
