package com.berkayb.soundconnect.modules.search.dto;

import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

public record ProfileSearchItemDto(
		String type,
		UUID targetId,
		UUID userId,
		String title,
		String subtitle,
		String imageUrl,
		@JsonInclude(JsonInclude.Include.NON_NULL)
		ListenerVisibilityMode visibilityMode
) {
	public ProfileSearchItemDto {
		visibilityMode = ghostOnly(visibilityMode);
	}

	public ProfileSearchItemDto(
			String type,
			UUID targetId,
			UUID userId,
			String title,
			String subtitle,
			String imageUrl
	) {
		this(type, targetId, userId, title, subtitle, imageUrl, null);
	}

	private static ListenerVisibilityMode ghostOnly(ListenerVisibilityMode visibilityMode) {
		return visibilityMode == ListenerVisibilityMode.GHOST ? ListenerVisibilityMode.GHOST : null;
	}
}
