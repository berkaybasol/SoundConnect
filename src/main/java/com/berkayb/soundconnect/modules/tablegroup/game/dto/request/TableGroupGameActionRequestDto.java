package com.berkayb.soundconnect.modules.tablegroup.game.dto.request;

import com.berkayb.soundconnect.modules.tablegroup.game.enums.TableGroupGameActionType;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record TableGroupGameActionRequestDto(
		@NotNull UUID requestId,
		@NotNull TableGroupGameActionType action,
		UUID targetUserId
) {
}
