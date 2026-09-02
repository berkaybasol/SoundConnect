package com.berkayb.soundconnect.modules.tablegroup.game.dto.response;

import com.berkayb.soundconnect.modules.tablegroup.game.enums.TableGroupGamePlayerStatus;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;
import java.util.UUID;

public record TableGroupGamePlayerResponseDto(
		UUID userId,
		String username,
		TableGroupGamePlayerStatus status,
		@JsonFormat(shape = JsonFormat.Shape.STRING) Instant joinedAt,
		boolean hasActed
) {
}
