package com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.response;

import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;

import java.time.Instant;
import java.util.UUID;

public record ListenerProfileResponseDto(
		UUID id,
		UUID userId,
		String username,
		String bio,
		UUID profilePictureMediaId,
		String profilePictureUrl,
		long followerCount,
		long followingCount,
		ListenerVisibilityMode visibilityMode,
		long version,
		Instant visibilityChangedAt
) {
	/** Compatibility constructor for existing internal callers and tests. */
	public ListenerProfileResponseDto(
			UUID id,
			UUID userId,
			String username,
			String bio,
			UUID profilePictureMediaId,
			String profilePictureUrl,
			long followerCount,
			long followingCount
	) {
		this(id, userId, username, bio, profilePictureMediaId, profilePictureUrl,
				followerCount, followingCount, ListenerVisibilityMode.STANDARD, 0L, null);
	}
}
