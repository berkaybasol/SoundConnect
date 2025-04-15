package com.berkayb.soundconnect.role.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Builder;

import java.util.Set;

@Builder
public record RoleRequest(
		
		@Schema(description = "Rol ismi", example = "ROLE_ADMIN")
		@NotBlank(message = "Role name cannot be blank.")
		String name,
		
		@Schema(
				description = "Permission ID listesi",
				example = "[1, 2, 3]",
				type = "array"
		)
		@NotEmpty(message = "Role must have at least one permission.") Set<Long> permissionIds

) {
}