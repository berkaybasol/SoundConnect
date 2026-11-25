package com.berkayb.soundconnect.modules.tablegroup.game.service;

import com.berkayb.soundconnect.modules.tablegroup.chat.dto.response.TableGroupMessageResponseDto;
import com.berkayb.soundconnect.modules.tablegroup.chat.enums.MessageType;
import com.berkayb.soundconnect.modules.tablegroup.game.dto.response.*;
import com.berkayb.soundconnect.modules.tablegroup.game.enums.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TableGroupGameVisibilityTest {
    @Test void twoPersonLobbyCanStartWhenOnlyTheCreatorIsVisibleToTheCreator() {
        UUID creator = UUID.randomUUID(), peer = UUID.randomUUID();
        Instant now = Instant.now();
        var game = new TableGroupGameResponseDto(1, UUID.randomUUID(), UUID.randomUUID(), 2, TableGroupGameTopic.WHO_PAYS,
                TableGroupGameMode.VOTE, TableGroupGameStatus.LOBBY, TableGroupGamePhase.LOBBY, creator, "creator", 0,
                now.plusSeconds(30), null, now, List.of(player(creator, "creator", now), player(peer, "peer", now)),
                List.of(), null, null, null, null, null);
        var dto = new TableGroupMessageResponseDto(UUID.randomUUID(), game.tableGroupId(), creator, "game", MessageType.GAME, now, null, game);
        var restricted = TableGroupGameVisibility.restrict(dto, Set.of(peer)).game();
        assertThat(restricted.players()).hasSize(1);
        assertThat(restricted.canStart()).isTrue();
    }

    @Test void sharedGameRemainsUsableWithoutExposingTheCreatorWinnerOrHistoricalVotes() {
        UUID hidden = UUID.randomUUID(), third = UUID.randomUUID(), viewer = UUID.randomUUID();
        Instant now = Instant.now();
        var game = new TableGroupGameResponseDto(1, UUID.randomUUID(), UUID.randomUUID(), 4L, TableGroupGameTopic.WHO_PAYS,
                TableGroupGameMode.VOTE, TableGroupGameStatus.COMPLETED, TableGroupGamePhase.COMPLETED,
                hidden, "hidden_name", 1, null, null, now,
                List.of(player(hidden, "hidden_name", now), player(third, "third", now), player(viewer, "viewer", now)),
                List.of(vote(hidden, third), vote(viewer, hidden), vote(third, viewer)), hidden, "hidden_name",
                TableGroupGameOutcome.ASSIGNED, "Hesap @hidden_name kullanıcısında", null);
        var dto = new TableGroupMessageResponseDto(UUID.randomUUID(), game.tableGroupId(), hidden,
                "@hidden_name", MessageType.GAME, now, null, game);
        var personal = TableGroupGameVisibility.restrict(dto, Set.of(hidden));
        assertThat(personal.senderId()).isNull();
        assertThat(personal.game().createdBy()).isNull();
        assertThat(personal.game().selectedUserId()).isNull();
        assertThat(personal.toString()).doesNotContain("hidden_name", hidden.toString());
        assertThat(personal.game().players()).extracting(TableGroupGamePlayerResponseDto::userId).containsExactly(third, viewer);
        assertThat(personal.game().revealedActions()).containsExactly(vote(third, viewer));
        var unrestricted = TableGroupGameVisibility.restrict(dto, Set.of());
        assertThat(unrestricted.game()).isSameAs(game);
        assertThat(unrestricted.content()).isEqualTo(game.resultMessage());
    }

    private TableGroupGamePlayerResponseDto player(UUID id, String name, Instant now) {
        return new TableGroupGamePlayerResponseDto(id, name, TableGroupGamePlayerStatus.ACTIVE, now, true);
    }

    private TableGroupGameRevealedActionResponseDto vote(UUID actor, UUID target) {
        return new TableGroupGameRevealedActionResponseDto(1, TableGroupGamePhase.VOTE, actor,
                TableGroupGameActionType.VOTE, target, null);
    }
}
