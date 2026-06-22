package com.berkayb.soundconnect.modules.follow.band.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;

public record BandFollowResponseDto(
		UUID followId,
		UUID followerId,
		String followerUsername,
		String followerProfilePicture,
		UUID bandId,
		String bandName,
		UUID bandProfilePictureMediaId,
		String bandProfilePictureUrl,
		LocalDateTime followedAt
) {
}