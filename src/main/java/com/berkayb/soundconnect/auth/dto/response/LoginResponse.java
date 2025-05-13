package com.berkayb.soundconnect.auth.dto.response;

import java.util.List;
import java.util.UUID;

public record LoginResponse(
		String id,
		String username,
		List<String> roles
) {
}