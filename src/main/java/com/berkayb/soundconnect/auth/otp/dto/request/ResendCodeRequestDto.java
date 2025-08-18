package com.berkayb.soundconnect.auth.otp.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record ResendCodeRequestDto(
		@NotBlank(message = "email boş olamaz")
		@Email(message = "geçerli bir email giriniz")
		String email
) {
}