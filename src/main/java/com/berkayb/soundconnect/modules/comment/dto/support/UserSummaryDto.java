package com.berkayb.soundconnect.modules.comment.dto.support;

import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

// yorum sahibinin minimal bilgisi flutterda commentlerde ve replylerde gorulecek alan
public record UserSummaryDto(
		UUID id,
		String username,
		String avatarUrl,
		@JsonInclude(JsonInclude.Include.NON_NULL)
		ListenerVisibilityMode visibilityMode
) {
	public UserSummaryDto {
		if (id == null) {
			username = "Anonymous Author";
			avatarUrl = null;
			visibilityMode = null;
		} else if (visibilityMode != ListenerVisibilityMode.GHOST) {
			visibilityMode = null;
		}
	}

	/** Keeps source compatibility for existing mapper and test call sites. */
	public UserSummaryDto(UUID id, String username, String avatarUrl) {
		this(id, username, avatarUrl, null);
	}
}
