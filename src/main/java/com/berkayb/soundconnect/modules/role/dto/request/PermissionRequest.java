package com.berkayb.soundconnect.modules.role.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Builder;

@Builder
public record PermissionRequest(
		@NotBlank(message = "Permission name cannot be blank.")
		String name
) {
}