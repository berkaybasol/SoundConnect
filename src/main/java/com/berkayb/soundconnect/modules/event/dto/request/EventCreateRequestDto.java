package com.berkayb.soundconnect.modules.event.dto.request;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * Yeni bir etkinlik oluşturmak için kullanılan DTO.
 * Burada bandId VE musicianProfileId aynı anda gönderilemez.
 * En fazla biri dolu olabilir.
 */
public record EventCreateRequestDto(
		
		@NotNull
		String title,
		
		String description,
		
		@NotNull
		LocalDate eventDate,
		
		@NotNull
		LocalTime startTime,
		
		LocalTime endTime,
		
		String posterImage,
		
		@NotNull
		UUID venueId,
		
		// performer seçenekleri:
		UUID musicianProfileId,
		UUID bandId

) {}