package com.berkayb.soundconnect.auth.passwordreset.dto.response;

public record PasswordResetAccountResponseDto(
		String username,
		String profilePictureUrl
) {
}
