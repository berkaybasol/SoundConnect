package com.berkayb.soundconnect.modules.profile.shared.resolver.dto;

import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

public record UserProfileTargetDto(
		String type,              // MUSICIAN, VENUE, LISTENER, STUDIO, ...
		UUID profileId,
		String displayName,
		String profilePictureUrl,
		@JsonInclude(JsonInclude.Include.NON_NULL)
		ListenerVisibilityMode visibilityMode
) {
	public UserProfileTargetDto {
		visibilityMode = ghostOnly(visibilityMode);
	}

	public UserProfileTargetDto(
			String type,
			UUID profileId,
			String displayName,
			String profilePictureUrl
	) {
		this(type, profileId, displayName, profilePictureUrl, null);
	}

	private static ListenerVisibilityMode ghostOnly(ListenerVisibilityMode visibilityMode) {
		return visibilityMode == ListenerVisibilityMode.GHOST ? ListenerVisibilityMode.GHOST : null;
	}
}
