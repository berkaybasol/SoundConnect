package com.berkayb.soundconnect.modules.tablegroup.game.dto.response;

import com.berkayb.soundconnect.modules.tablegroup.game.enums.*;
import com.fasterxml.jackson.annotation.JsonFormat;

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
		int round,
		@JsonFormat(shape = JsonFormat.Shape.STRING) Instant joinDeadlineAt,
		@JsonFormat(shape = JsonFormat.Shape.STRING) Instant actionDeadlineAt,
		@JsonFormat(shape = JsonFormat.Shape.STRING) Instant serverTime,
		List<TableGroupGamePlayerResponseDto> players,
		List<TableGroupGameRevealedActionResponseDto> revealedActions,
		UUID selectedUserId,
		String selectedUsername,
		TableGroupGameOutcome outcome,
		String resultMessage,
		String cancellationReason
) {
}
