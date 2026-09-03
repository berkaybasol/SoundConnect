package com.berkayb.soundconnect.modules.tablegroup.dto.response;

import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import com.berkayb.soundconnect.modules.tablegroup.enums.ParticipantStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

@Builder
public record TableGroupParticipantDto(
		UUID userId,
		Instant joinedAt,
		ParticipantStatus status,
		String joinNote,
		String username, //eklendi
		String profilePictureUrl, //eklendi
		@JsonInclude(JsonInclude.Include.NON_NULL)
		ListenerVisibilityMode visibilityMode
) {
	public TableGroupParticipantDto {
		visibilityMode = ghostOnly(visibilityMode);
	}

	public TableGroupParticipantDto(
			UUID userId,
			Instant joinedAt,
			ParticipantStatus status,
			String joinNote,
			String username,
			String profilePictureUrl
	) {
		this(userId, joinedAt, status, joinNote, username, profilePictureUrl, null);
	}

	private static ListenerVisibilityMode ghostOnly(ListenerVisibilityMode visibilityMode) {
		return visibilityMode == ListenerVisibilityMode.GHOST ? ListenerVisibilityMode.GHOST : null;
	}
}
