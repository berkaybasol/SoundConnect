package com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.request;

import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Sets a desired state rather than toggling blindly. The version protects a
 * user's more recent visibility choice from a stale device while exact retries
 * remain idempotent.
 */
public record ListenerVisibilityUpdateRequestDto(
		@NotNull ListenerVisibilityMode visibilityMode,
		@NotNull @PositiveOrZero Long expectedVersion
) {
}
