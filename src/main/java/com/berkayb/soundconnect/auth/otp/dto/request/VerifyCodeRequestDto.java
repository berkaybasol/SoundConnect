package com.berkayb.soundconnect.auth.otp.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record VerifyCodeRequestDto(
		@NotBlank(message = "email boş olamaz")
		@Email(message = "geçerli bir email giriniz")
		@Size(max = 254, message = "email en fazla 254 karakter olabilir")
		String email,
		
		@NotBlank(message = "kod boş olamaz")
		@Pattern(regexp = "^\\d{6}$", message = "kod 6 haneli olmalı")
		String code
) {
}
