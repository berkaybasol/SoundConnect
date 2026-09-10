package com.berkayb.soundconnect.modules.tablegroup.game.service;

import com.berkayb.soundconnect.modules.tablegroup.chat.repository.TableGroupMessageRepository;
import com.berkayb.soundconnect.modules.tablegroup.game.realtime.TableGroupGameRealtimePublisher;
import com.berkayb.soundconnect.modules.tablegroup.game.repository.TableGroupGameRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

/** Rebuild from committed identity/state; an erasure transaction must never capture a player's old identity. */
@Service @RequiredArgsConstructor
public class TableGroupErasureGamePublisher {
    private final TableGroupGameRepository games;
    private final TableGroupMessageRepository messages;
    private final TableGroupGameProjectionService projections;
    private final TableGroupGameRealtimePublisher realtime;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void refresh(UUID gameId) {
        var game = games.findById(gameId).orElse(null);
        if (game == null) return;
        var anchor = messages.findByGameIdAndDeletedAtIsNull(gameId).orElse(null);
        if (anchor == null) return;
        realtime.publishUpdatedAfterCommit(game.getTableGroupId(), anchor, projections.project(game));
    }
}
