package com.berkayb.soundconnect.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record GoogleAuthRequestDto(
		@NotBlank
		@Size(max = 16_384)
		String idToken
) {
}
