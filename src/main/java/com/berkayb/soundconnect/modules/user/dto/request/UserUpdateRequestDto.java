package com.berkayb.soundconnect.modules.user.dto.request;

import com.berkayb.soundconnect.shared.validation.BcryptPasswordLength;
import com.berkayb.soundconnect.shared.util.UsernameUtils;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record UserUpdateRequestDto(
		@Size(min = UsernameUtils.MIN_LENGTH, max = UsernameUtils.MAX_LENGTH)
		String username,
		@Size(min = 8, max = 72)
		@BcryptPasswordLength
		String password,
		@Email
		@Size(min = 3, max = 254)
		String email,
		UUID roleId
) {
	public UserUpdateRequestDto {
		username = UsernameUtils.normalize(username);
	}
}
