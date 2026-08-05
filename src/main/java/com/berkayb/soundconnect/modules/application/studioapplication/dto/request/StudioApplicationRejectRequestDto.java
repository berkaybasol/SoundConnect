package com.berkayb.soundconnect.modules.application.studioapplication.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record StudioApplicationRejectRequestDto(
		@NotBlank(message = "Ret gerekcesi zorunludur")
		@Size(max = 500, message = "Ret gerekcesi en fazla 500 karakter olabilir")
		String reason
) {}
