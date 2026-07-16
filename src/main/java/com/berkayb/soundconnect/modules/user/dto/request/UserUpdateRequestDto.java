package com.berkayb.soundconnect.modules.user.dto.request;

import com.berkayb.soundconnect.shared.validation.BcryptPasswordLength;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record UserUpdateRequestDto(
		@Size(min = 3, max = 30)
		String username,
		@Size(min = 8, max = 72)
		@BcryptPasswordLength
		String password,
		@Email
		@Size(min = 3, max = 254)
		String email,
		UUID roleId
) {
}
