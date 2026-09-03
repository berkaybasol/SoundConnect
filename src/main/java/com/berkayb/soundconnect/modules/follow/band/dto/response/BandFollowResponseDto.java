package com.berkayb.soundconnect.modules.follow.band.dto.response;

import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.UUID;

public record BandFollowResponseDto(
		UUID followId,
		UUID followerId,
		String followerUsername,
		String followerProfilePicture,
		@JsonInclude(JsonInclude.Include.NON_NULL)
		ListenerVisibilityMode followerVisibilityMode,
		UUID bandId,
		String bandName,
		UUID bandProfilePictureMediaId,
		String bandProfilePictureUrl,
		LocalDateTime followedAt
) {
	public BandFollowResponseDto {
		followerVisibilityMode = followerVisibilityMode == ListenerVisibilityMode.GHOST
				? followerVisibilityMode
				: null;
	}

	/** Keeps source compatibility for existing mapper and test call sites. */
	public BandFollowResponseDto(
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
		this(
				followId,
				followerId,
				followerUsername,
				followerProfilePicture,
				null,
				bandId,
				bandName,
				bandProfilePictureMediaId,
				bandProfilePictureUrl,
				followedAt
		);
	}
}
