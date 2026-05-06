package com.berkayb.soundconnect.auth.dto.response;

import com.berkayb.soundconnect.modules.user.enums.UserStatus;

public record LoginResponse(
		String token,
		UserStatus status
) {
}
