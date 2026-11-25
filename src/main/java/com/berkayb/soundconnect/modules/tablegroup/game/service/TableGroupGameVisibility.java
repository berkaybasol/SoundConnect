package com.berkayb.soundconnect.modules.tablegroup.game.service;

import com.berkayb.soundconnect.modules.tablegroup.chat.dto.response.TableGroupMessageResponseDto;
import com.berkayb.soundconnect.modules.tablegroup.game.dto.response.TableGroupGameResponseDto;
import com.berkayb.soundconnect.modules.user.block.UserBlockService;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** A game is shared even when two players cannot interact. Apply the same projection to every delivery path. */
public final class TableGroupGameVisibility {
    private TableGroupGameVisibility() {}

    public static Set<UUID> identities(Collection<TableGroupGameResponseDto> games) {
        Set<UUID> ids = new HashSet<>();
        for (var game : games) {
            ids.add(game.createdBy());
            ids.add(game.selectedUserId());
            game.players().forEach(player -> ids.add(player.userId()));
            game.revealedActions().forEach(action -> {
                ids.add(action.actorUserId());
                ids.add(action.targetUserId());
            });
        }
        ids.remove(null);
        return ids;
    }

    public static TableGroupMessageResponseDto forViewer(UserBlockService blocks, UUID viewer,
                                                          TableGroupMessageResponseDto message) {
        if (message == null || message.game() == null) return message;
        var ids = identities(java.util.List.of(message.game()));
        if (message.senderId() != null) ids.add(message.senderId());
        return restrict(message, blocks.blockedAmong(viewer, ids));
    }

    public static TableGroupMessageResponseDto restrict(TableGroupMessageResponseDto message, Set<UUID> hidden) {
        var game = message.game();
        boolean hideSender = hidden(message.senderId(), hidden);
        boolean hideCreator = hidden(game.createdBy(), hidden);
        boolean hideResult = hidden(game.selectedUserId(), hidden);
        String result = hideResult ? "Oyun tamamlandı." : game.resultMessage();
        var visible = hidden.isEmpty() ? game : new TableGroupGameResponseDto(
                game.schemaVersion(), game.gameId(), game.tableGroupId(), game.revision(), game.topic(), game.mode(),
                game.status(), game.phase(), hideCreator ? null : game.createdBy(),
                hideCreator ? null : game.createdByUsername(), hideCreator ? null : game.createdByVisibilityMode(),
                game.round(), game.joinDeadlineAt(), game.actionDeadlineAt(), game.serverTime(),
                game.players().stream().filter(p -> !hidden(p.userId(), hidden)).toList(),
                game.revealedActions().stream().filter(a -> !hidden(a.actorUserId(), hidden)
                        && !hidden(a.targetUserId(), hidden)).toList(),
                hideResult ? null : game.selectedUserId(), hideResult ? null : game.selectedUsername(),
                hideResult ? null : game.selectedUserVisibilityMode(), game.outcome(), result, game.cancellationReason(),
                game.canStart());
        // The persisted anchor can contain the original winner's @username, including for Ghost listeners.
        String content = result == null ? message.content() : result;
        return new TableGroupMessageResponseDto(message.messageId(), message.tableGroupId(),
                hideSender ? null : message.senderId(), content, message.messageType(), message.sentAt(),
                message.deletedAt(), message.clientMessageId(), visible);
    }

    private static boolean hidden(UUID user, Set<UUID> hidden) {
        return user != null && hidden.contains(user);
    }
}
