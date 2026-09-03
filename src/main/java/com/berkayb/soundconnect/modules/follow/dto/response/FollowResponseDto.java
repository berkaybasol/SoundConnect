package com.berkayb.soundconnect.modules.follow.dto.response;

import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.UUID;

public record FollowResponseDto(
		UUID id,
		UUID followerId,
		String followerUsername,
		String followerProfilePicture,
		@JsonInclude(JsonInclude.Include.NON_NULL)
		ListenerVisibilityMode followerVisibilityMode,
		UUID followingId,
		String followingUsername,
		String followingProfilePicture,
		@JsonInclude(JsonInclude.Include.NON_NULL)
		ListenerVisibilityMode followingVisibilityMode,
		LocalDateTime followedAt
) {
	public FollowResponseDto {
		followerVisibilityMode = ghostOnly(followerVisibilityMode);
		followingVisibilityMode = ghostOnly(followingVisibilityMode);
	}

	/** Keeps source compatibility for existing mapper and test call sites. */
	public FollowResponseDto(
			UUID id,
			UUID followerId,
			String followerUsername,
			String followerProfilePicture,
			UUID followingId,
			String followingUsername,
			String followingProfilePicture,
			LocalDateTime followedAt
	) {
		this(
				id,
				followerId,
				followerUsername,
				followerProfilePicture,
				null,
				followingId,
				followingUsername,
				followingProfilePicture,
				null,
				followedAt
		);
	}

	private static ListenerVisibilityMode ghostOnly(ListenerVisibilityMode mode) {
		return mode == ListenerVisibilityMode.GHOST ? mode : null;
	}
}
