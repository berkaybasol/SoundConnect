package com.berkayb.soundconnect.modules.user.dto.request;

import com.berkayb.soundconnect.shared.validation.BcryptPasswordLength;
import com.berkayb.soundconnect.shared.util.UsernameUtils;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record UserSaveRequestDto(
		@NotBlank
		@Size(min = UsernameUtils.MIN_LENGTH, max = UsernameUtils.MAX_LENGTH)
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
	public UserSaveRequestDto {
		username = UsernameUtils.normalize(username);
	}
}
