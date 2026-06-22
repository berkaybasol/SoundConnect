package com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.response;

import java.util.UUID;

public record ListenerProfileResponseDto(
		UUID id,
		UUID userId,
		String username,
		String bio,
		UUID profilePictureMediaId,
		String profilePictureUrl,
		long followerCount,
		long followingCount
) {}