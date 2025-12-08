package com.berkayb.soundconnect.modules.follow.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;

public record FollowResponseDto(
		UUID id,
		UUID followerId,
		String followerUsername,
		String followerProfilePicture,
		UUID followingId,
		String followingUsername,
		String followingProfilePicture,
		LocalDateTime followedAt
) {}