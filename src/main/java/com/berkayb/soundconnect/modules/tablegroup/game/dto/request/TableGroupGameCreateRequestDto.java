package com.berkayb.soundconnect.modules.tablegroup.game.dto.request;

import com.berkayb.soundconnect.modules.tablegroup.game.enums.TableGroupGameMode;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record TableGroupGameCreateRequestDto(
		@NotNull UUID requestId,
		@NotNull TableGroupGameMode mode
) {
}
