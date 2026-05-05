package com.berkayb.soundconnect.modules.follow.dto.request;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record FollowRequestDto(
		UUID followerId,
		
		@NotNull (message = "Following user ID is required")
		UUID followingId
) {
}
