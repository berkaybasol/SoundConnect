package com.berkayb.soundconnect.auth.passwordreset.dto.request;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ForgotPasswordRequestDto(
		@JsonAlias("email")
		@NotBlank(message = "Kullanıcı adı veya e-posta boş olamaz.")
		@Size(max = 254, message = "Kullanıcı adı veya e-posta en fazla 254 karakter olabilir.")
		String identifier
) {
}
