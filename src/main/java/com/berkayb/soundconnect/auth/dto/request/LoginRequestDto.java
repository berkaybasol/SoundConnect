package com.berkayb.soundconnect.auth.dto.request;

import com.berkayb.soundconnect.shared.validation.BcryptPasswordLength;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequestDto(
		@NotBlank(message = "kullanıcı adı boş olamaz")
		@Size(min = 3, max = 30, message = "kullanici adi 3-30 karakter olmali")
		String username,
		
		@NotBlank(message = "şifre boş olamaz")
		@Size(max = 72, message = "Şifre en fazla 72 karakter olmalı")
		@BcryptPasswordLength
		String password
) {
}
