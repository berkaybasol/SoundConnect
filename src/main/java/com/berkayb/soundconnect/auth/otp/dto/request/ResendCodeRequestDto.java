package com.berkayb.soundconnect.auth.otp.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResendCodeRequestDto(
		@NotBlank(message = "email boş olamaz")
		@Email(message = "geçerli bir email giriniz")
		@Size(max = 254, message = "email en fazla 254 karakter olabilir")
		String email
) {
}
