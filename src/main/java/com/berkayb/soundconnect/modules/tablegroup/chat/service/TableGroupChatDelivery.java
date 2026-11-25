package com.berkayb.soundconnect.modules.tablegroup.chat.service;

import com.berkayb.soundconnect.modules.notification.service.AfterCommitDeliveryExecutor;
import com.berkayb.soundconnect.modules.tablegroup.chat.cache.TableGroupChatUnreadHelper;
import com.berkayb.soundconnect.modules.tablegroup.chat.mapper.TableGroupMessageMapper;
import com.berkayb.soundconnect.modules.tablegroup.chat.repository.TableGroupMessageRepository;
import com.berkayb.soundconnect.modules.tablegroup.entity.TableGroup;
import com.berkayb.soundconnect.modules.tablegroup.entity.TableGroupParticipant;
import com.berkayb.soundconnect.modules.tablegroup.enums.ParticipantStatus;
import com.berkayb.soundconnect.modules.tablegroup.enums.TableGroupStatus;
import com.berkayb.soundconnect.modules.tablegroup.game.service.TableGroupGameProjectionService;
import com.berkayb.soundconnect.modules.tablegroup.game.service.TableGroupGameVisibility;
import com.berkayb.soundconnect.modules.tablegroup.observability.TableGroupMetrics;
import com.berkayb.soundconnect.modules.tablegroup.repository.TableGroupRepository;
import com.berkayb.soundconnect.modules.user.block.UserBlockService;
import com.berkayb.soundconnect.shared.realtime.WebSocketChannels;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class TableGroupChatDelivery {
    private final AfterCommitDeliveryExecutor executor;
    private final TransactionTemplate transactions;
    private final TableGroupRepository tables;
    private final TableGroupMessageRepository messages;
    private final TableGroupMessageMapper mapper;
    private final TableGroupGameProjectionService games;
    private final UserBlockService blocks;
    private final TableGroupChatUnreadHelper unread;
    private final SimpMessagingTemplate broker;
    private final TableGroupMetrics metrics;

    public static Set<UUID> acceptedUsers(TableGroup table) {
        return table.getParticipants().stream()
                .filter(p -> p.getStatus() == ParticipantStatus.ACCEPTED)
                .map(TableGroupParticipant::getUserId).filter(Objects::nonNull).collect(Collectors.toSet());
    }

    /** Called only after commit. Re-read membership, moderation and visibility on the delivery thread. */
    public void enqueue(UUID tableId, UUID messageId, Set<UUID> initialUnreadAudience) {
        try {
            executor.submit(() -> transactions.executeWithoutResult(tx -> publish(tableId, messageId, initialUnreadAudience)));
        } catch (RuntimeException failure) {
            log.warn("Committed table chat delivery could not be queued: tableId={}", tableId, failure);
        }
    }

    void publish(UUID tableId, UUID messageId, Set<UUID> initialUnreadAudience) {
        var table = tables.findById(tableId).orElse(null);
        if (table == null || table.getStatus() != TableGroupStatus.ACTIVE
                || !table.getExpiresAt().isAfter(Instant.now())) return;
        Set<UUID> audience = acceptedUsers(table);
        var row = messages.findById(messageId).orElse(null);
        if (row == null || !tableId.equals(row.getTableGroupId()) || audience.isEmpty()) return;
        var payload = mapper.toResponseDto(row);
        if (row.getGameId() != null) {
            var game = games.find(row.getGameId()).orElse(null);
            if (game == null) return;
            payload = mapper.toResponseDto(row, game);
        }
        Set<UUID> senderBlocks = blocks.blockedAmong(row.getSenderId(), audience);
        for (UUID viewer : audience) {
            if (payload.game() == null && senderBlocks.contains(viewer)) continue;
            try {
                var visible = TableGroupGameVisibility.forViewer(blocks, viewer, payload);
                if (row.getDeletedAt() == null && !viewer.equals(row.getSenderId()) && !senderBlocks.contains(viewer)
                        && initialUnreadAudience.contains(viewer)) increment(viewer, tableId);
                broker.convertAndSend(WebSocketChannels.tableGroup(tableId, viewer), visible);
            } catch (RuntimeException failure) {
                metrics.realtimePublishFailed();
                log.warn("Table chat delivery failed: tableId={}, messageId={}, viewer={}", tableId, messageId, viewer, failure);
            }
        }
    }

    private void increment(UUID viewer, UUID tableId) {
        try {
            unread.incrementUnread(viewer, tableId);
        } catch (RuntimeException failure) {
            metrics.unreadCacheFailed("delivery");
            log.warn("Table chat unread update failed: tableId={}, viewer={}", tableId, viewer, failure);
        }
    }
}
