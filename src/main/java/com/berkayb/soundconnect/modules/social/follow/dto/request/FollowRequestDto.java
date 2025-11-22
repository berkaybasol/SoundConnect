package com.berkayb.soundconnect.modules.social.follow.dto.request;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record FollowRequestDto(
		@NotNull (message = "Follower user ID is required")
		UUID followerId,
		
		@NotNull (message = "Following user ID is required")
		UUID followingId
) {
}