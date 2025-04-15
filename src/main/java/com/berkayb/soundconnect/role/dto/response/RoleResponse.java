package com.berkayb.soundconnect.role.dto.response;

import java.util.Set;


public record RoleResponse(
		Long id,
		String name,
		Set<PermissionResponse> permissions
) {
}