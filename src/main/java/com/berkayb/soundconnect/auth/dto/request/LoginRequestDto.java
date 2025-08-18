package com.berkayb.soundconnect.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequestDto(
		@NotBlank(message = "kullanıcı adı boş olamaz")
		String username,
		
		@NotBlank(message = "şifre boş olamaz")
		@Size(min = 3, max = 30, message = "kullanıcı adı 3-30 karakter olmalı")
		String password
) {
}