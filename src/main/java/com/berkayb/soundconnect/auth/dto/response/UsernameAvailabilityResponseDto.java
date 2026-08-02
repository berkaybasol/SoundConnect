package com.berkayb.soundconnect.auth.dto.response;

public record UsernameAvailabilityResponseDto(
		String username,
		boolean available
) {
}
