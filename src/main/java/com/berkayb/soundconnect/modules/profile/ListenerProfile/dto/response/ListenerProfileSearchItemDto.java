package com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.response;

import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ListenerProfileSearchItemDto(
		UUID profileId,
		UUID userId,
		String username,
		String bio,
		String profilePictureUrl,
		ListenerVisibilityMode visibilityMode
) {
	/** Compatibility constructor for existing callers. */
	public ListenerProfileSearchItemDto(
			UUID profileId,
			UUID userId,
			String username,
			String bio,
			String profilePictureUrl
	) {
		this(profileId, userId, username, bio, profilePictureUrl, ListenerVisibilityMode.STANDARD);
	}
}
