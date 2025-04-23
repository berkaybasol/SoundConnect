package com.berkayb.soundconnect.modules.role.dto.response;

import java.util.Set;
import java.util.UUID;


public record RoleResponse(
		UUID id,
		String name,
		Set<PermissionResponse> permissions
) {
}