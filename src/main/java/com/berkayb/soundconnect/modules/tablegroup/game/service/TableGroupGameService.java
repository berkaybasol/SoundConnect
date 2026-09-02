package com.berkayb.soundconnect.modules.tablegroup.game.service;

import com.berkayb.soundconnect.modules.tablegroup.game.dto.request.*;
import com.berkayb.soundconnect.modules.tablegroup.chat.dto.response.TableGroupMessageResponseDto;

import java.util.Optional;
import java.util.UUID;

public interface TableGroupGameService {
	TableGroupMessageResponseDto create(
			UUID requesterId,
			UUID tableGroupId,
			TableGroupGameCreateRequestDto request
	);

	Optional<TableGroupMessageResponseDto> getActive(UUID requesterId, UUID tableGroupId);
	TableGroupMessageResponseDto get(UUID requesterId, UUID tableGroupId, UUID gameId);
	TableGroupMessageResponseDto join(UUID requesterId, UUID tableGroupId, UUID gameId);
	TableGroupMessageResponseDto leave(UUID requesterId, UUID tableGroupId, UUID gameId);
	TableGroupMessageResponseDto start(UUID requesterId, UUID tableGroupId, UUID gameId);
	TableGroupMessageResponseDto cancel(UUID requesterId, UUID tableGroupId, UUID gameId);
	TableGroupMessageResponseDto submitAction(
			UUID requesterId,
			UUID tableGroupId,
			UUID gameId,
			TableGroupGameActionRequestDto request
	);
}
