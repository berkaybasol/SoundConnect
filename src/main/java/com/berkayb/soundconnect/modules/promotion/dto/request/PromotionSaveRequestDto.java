package com.berkayb.soundconnect.modules.promotion.dto.request;

import com.berkayb.soundconnect.modules.promotion.enums.PromotionPlacement;
import com.berkayb.soundconnect.modules.promotion.enums.PromotionStatus;
import com.berkayb.soundconnect.modules.promotion.enums.PromotionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

public record PromotionSaveRequestDto(

		// promotion tipi
		@NotNull(message = "Promotion type bos olamaz")
		PromotionType type,
		
		// promotion nerede gosterilecek
		@NotNull(message = "Promotion placement bos olamaz")
		PromotionPlacement placement,
		
		// promoiton durumu
		@NotNull(message = "promotion status bos olamaz")
		PromotionStatus status,
		
		// kullaniciya gosterilcek baslik
		@NotBlank(message = "promotion title bos olamaz")
		@Size(max = 150, message = "Promotion title en fazla 150 karakter olabilir")
		String title,
		
		@Size(max = 500, message = "Promotion description en fazla 500 karakter olabilir")
		String description,
		
		@NotNull(message = "Media asset id bos olamaz")
		UUID mediaAssetId,
		
		@Size(max = 500, message = "Promotion redirect url en fazla 500 karakter olabilir")
		String redirectUrl,
		
		@NotNull(message = "priority bos olmaz")
		Integer priority,
		
		LocalDateTime startDate,
		
		LocalDateTime endDate

) {
}