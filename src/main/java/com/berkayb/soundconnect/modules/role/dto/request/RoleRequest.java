package com.berkayb.soundconnect.modules.role.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Builder;

import java.util.Set;
import java.util.UUID;

@Builder
public record RoleRequest(
		
		@Schema(description = "Rol ismi", example = "ROLE_ADMIN")
		@NotBlank(message = "Role name cannot be blank.")
		String name,
		
		
		@NotEmpty(message = "Role must have at least one permission.")
		Set<UUID> permissionIds

) {
}