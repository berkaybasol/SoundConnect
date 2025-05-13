package com.berkayb.soundconnect.auth.dto.response;

import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.role.enums.RoleEnum;

import java.util.List;

public record LoginResponse(
		String token,
		List<RoleEnum> roles
) {
}