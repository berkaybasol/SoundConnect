package com.berkayb.soundconnect.modules.tablegroup.game.dto.response;

import com.berkayb.soundconnect.modules.tablegroup.game.enums.TableGroupGamePlayerStatus;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

public record TableGroupGamePlayerResponseDto(
		UUID userId,
		String username,
		TableGroupGamePlayerStatus status,
		@JsonFormat(shape = JsonFormat.Shape.STRING) Instant joinedAt,
		boolean hasActed,
		@JsonInclude(JsonInclude.Include.NON_NULL)
		ListenerVisibilityMode visibilityMode
) {
	public TableGroupGamePlayerResponseDto {
		if (visibilityMode != ListenerVisibilityMode.GHOST) {
			visibilityMode = null;
		}
	}

	/** Keeps source compatibility for projections without contextual visibility. */
	public TableGroupGamePlayerResponseDto(
			UUID userId,
			String username,
			TableGroupGamePlayerStatus status,
			Instant joinedAt,
			boolean hasActed
	) {
		this(userId, username, status, joinedAt, hasActed, null);
	}
}
