package com.berkayb.soundconnect.modules.tablegroup.game.dto.response;

import com.berkayb.soundconnect.modules.tablegroup.game.enums.TableGroupGameActionType;
import com.berkayb.soundconnect.modules.tablegroup.game.enums.TableGroupGamePhase;

import java.util.UUID;

public record TableGroupGameRevealedActionResponseDto(
		int round,
		TableGroupGamePhase phase,
		UUID actorUserId,
		TableGroupGameActionType action,
		UUID targetUserId,
		Integer value
) {
}
