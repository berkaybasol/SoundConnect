package com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.response;

import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.UUID;

/**
 * Safe public projection. Null showcase/social fields are omitted from JSON in
 * ghost mode rather than being represented by misleading empty values.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ListenerProfilePublicResponseDto(
		UUID id,
		UUID userId,
		String username,
		ListenerVisibilityMode visibilityMode,
		String bio,
		UUID profilePictureMediaId,
		String profilePictureUrl,
		Long followerCount,
		Long followingCount,
		boolean restricted,
		boolean canFollow,
		boolean canMessage,
		List<ListenerPlaylistResponseDto> playlists
) {
}
