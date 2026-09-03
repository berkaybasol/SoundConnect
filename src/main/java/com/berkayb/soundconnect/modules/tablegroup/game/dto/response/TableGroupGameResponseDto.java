package com.berkayb.soundconnect.modules.tablegroup.game.dto.response;

import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import com.berkayb.soundconnect.modules.tablegroup.game.enums.*;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TableGroupGameResponseDto(
		int schemaVersion,
		UUID gameId,
		UUID tableGroupId,
		long revision,
		TableGroupGameTopic topic,
		TableGroupGameMode mode,
		TableGroupGameStatus status,
		TableGroupGamePhase phase,
		UUID createdBy,
		String createdByUsername,
		@JsonInclude(JsonInclude.Include.NON_NULL)
		ListenerVisibilityMode createdByVisibilityMode,
		int round,
		@JsonFormat(shape = JsonFormat.Shape.STRING) Instant joinDeadlineAt,
		@JsonFormat(shape = JsonFormat.Shape.STRING) Instant actionDeadlineAt,
		@JsonFormat(shape = JsonFormat.Shape.STRING) Instant serverTime,
		List<TableGroupGamePlayerResponseDto> players,
		List<TableGroupGameRevealedActionResponseDto> revealedActions,
		UUID selectedUserId,
		String selectedUsername,
		@JsonInclude(JsonInclude.Include.NON_NULL)
		ListenerVisibilityMode selectedUserVisibilityMode,
		TableGroupGameOutcome outcome,
		String resultMessage,
		String cancellationReason
) {
	public TableGroupGameResponseDto {
		if (createdByVisibilityMode != ListenerVisibilityMode.GHOST) {
			createdByVisibilityMode = null;
		}
		if (selectedUserVisibilityMode != ListenerVisibilityMode.GHOST) {
			selectedUserVisibilityMode = null;
		}
	}

	/** Keeps source compatibility for existing game response fixtures. */
	public TableGroupGameResponseDto(
			int schemaVersion,
			UUID gameId,
			UUID tableGroupId,
			long revision,
			TableGroupGameTopic topic,
			TableGroupGameMode mode,
			TableGroupGameStatus status,
			TableGroupGamePhase phase,
			UUID createdBy,
			String createdByUsername,
			int round,
			Instant joinDeadlineAt,
			Instant actionDeadlineAt,
			Instant serverTime,
			List<TableGroupGamePlayerResponseDto> players,
			List<TableGroupGameRevealedActionResponseDto> revealedActions,
			UUID selectedUserId,
			String selectedUsername,
			TableGroupGameOutcome outcome,
			String resultMessage,
			String cancellationReason
	) {
		this(
				schemaVersion,
				gameId,
				tableGroupId,
				revision,
				topic,
				mode,
				status,
				phase,
				createdBy,
				createdByUsername,
				null,
				round,
				joinDeadlineAt,
				actionDeadlineAt,
				serverTime,
				players,
				revealedActions,
				selectedUserId,
				selectedUsername,
				null,
				outcome,
				resultMessage,
				cancellationReason
		);
	}
}
