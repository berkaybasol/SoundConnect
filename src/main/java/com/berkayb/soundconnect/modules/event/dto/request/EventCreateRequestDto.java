package com.berkayb.soundconnect.modules.event.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * Yeni bir etkinlik oluşturmak için kullanılan DTO.
 * Burada bandId VE musicianProfileId aynı anda gönderilemez.
 * En fazla biri dolu olabilir.
 */
public record EventCreateRequestDto(
		
		@NotBlank
		@Size(max = 255, message = "Etkinlik başlığı en fazla 255 karakter olabilir.")
		String title,
		
		@Size(max = 500, message = "Etkinlik açıklaması en fazla 500 karakter olabilir.")
		String description,
		
		@NotNull
		LocalDate eventDate,
		
		@NotNull
		LocalTime startTime,
		
		LocalTime endTime,
		
		@Size(max = 255, message = "Etkinlik afiş referansı en fazla 255 karakter olabilir.")
		String posterImage,
		
		@NotNull
		UUID venueId,
		
		// performer seçenekleri:
		UUID musicianProfileId,
		UUID bandId,
		@Size(max = 120, message = "Sanatçı adı en fazla 120 karakter olabilir.")
		String manualPerformerName

) {}
