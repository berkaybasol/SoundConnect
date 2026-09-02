package com.berkayb.soundconnect.modules.tablegroup.game.service;

import com.berkayb.soundconnect.modules.tablegroup.entity.TableGroup;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TableGroupGameLifecycleService {
	private final TableGroupGameServiceImpl gameService;

	public void participantRemoved(TableGroup lockedTableGroup, UUID userId, String reason) {
		gameService.removeTableParticipantLocked(lockedTableGroup, userId, reason);
	}

	public void tableClosed(TableGroup lockedTableGroup, String reason) {
		gameService.closeActiveGameLocked(lockedTableGroup, reason);
	}

	public int purgeForTableGroups(Collection<UUID> tableGroupIds) {
		return gameService.purgeGamesForTableGroups(tableGroupIds);
	}
}
