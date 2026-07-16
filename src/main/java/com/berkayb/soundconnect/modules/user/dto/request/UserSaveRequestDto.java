package com.berkayb.soundconnect.modules.user.dto.request;

import com.berkayb.soundconnect.shared.validation.BcryptPasswordLength;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record UserSaveRequestDto(
		@NotBlank
		@Size(min = 3, max = 30)
		String username,
		@NotBlank
		@Email
		@Size(max = 254)
		String email,
		@NotNull
		UUID roleId,
		@NotBlank
		@Size(min = 8, max = 72)
		@BcryptPasswordLength
		String password
) {
}
