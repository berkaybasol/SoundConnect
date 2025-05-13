package com.berkayb.soundconnect.auth.dto.response;

import java.util.List;

public record LoginResponse(
		String token,
		List<String> roles
) {
}