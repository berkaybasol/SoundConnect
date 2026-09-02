package com.berkayb.soundconnect.modules.tablegroup.game.service;

import com.berkayb.soundconnect.modules.tablegroup.game.dto.response.TableGroupGameResponseDto;
import com.berkayb.soundconnect.modules.tablegroup.game.entity.*;
import com.berkayb.soundconnect.modules.tablegroup.game.enums.*;
import com.berkayb.soundconnect.modules.tablegroup.game.repository.*;
import com.berkayb.soundconnect.modules.tablegroup.game.support.TableGroupGameTimeProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TableGroupGameProjectionServiceTest {
	@Mock TableGroupGameRepository gameRepository;
	@Mock TableGroupGamePlayerRepository playerRepository;
	@Mock TableGroupGameActionRepository actionRepository;
	@Mock TableGroupGameTimeProvider timeProvider;
	@InjectMocks TableGroupGameProjectionService service;

	@Test
	void batchProjectionRequiresRepeatableReadWhenCalledOutsideChatHistory()
			throws NoSuchMethodException {
		Transactional transaction = TableGroupGameProjectionService.class
				.getMethod("projectByIds", Collection.class)
				.getAnnotation(Transactional.class);

		assertThat(transaction).isNotNull();
		assertThat(transaction.readOnly()).isTrue();
		assertThat(transaction.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
	}

	@Test
	void projectNeverLeaksPendingChoicesButReportsWhoActed() {
		UUID gameId = UUID.randomUUID();
		UUID first = UUID.randomUUID();
		UUID second = UUID.randomUUID();
		Instant now = Instant.parse("2026-08-30T12:00:00Z");
		TableGroupGame game = TableGroupGame.builder()
				.id(gameId)
				.tableGroupId(UUID.randomUUID())
				.createdBy(first)
				.createdByUsername("ece")
				.createRequestId(UUID.randomUUID())
				.topic(TableGroupGameTopic.WHO_PAYS)
				.mode(TableGroupGameMode.ROCK_PAPER_SCISSORS)
				.status(TableGroupGameStatus.IN_PROGRESS)
				.phase(TableGroupGamePhase.RPS)
				.roundNumber(2)
				.revision(7)
				.actionDeadlineAt(now.plusSeconds(20))
				.build();
		List<TableGroupGamePlayer> players = List.of(
				player(gameId, first, "ece", now),
				player(gameId, second, "mert", now)
		);
		TableGroupGameAction oldReveal = action(gameId, first, 1, true, TableGroupGameActionType.ROCK);
		TableGroupGameAction pending = action(gameId, first, 2, false, TableGroupGameActionType.SCISSORS);
		when(playerRepository.findByGameIdOrderByJoinedAtAscIdAsc(gameId)).thenReturn(players);
		when(actionRepository.findByGameIdInOrderByGameIdAscRoundNumberAscCreatedAtAscIdAsc(List.of(gameId)))
				.thenReturn(List.of(oldReveal, pending));
		when(timeProvider.now()).thenReturn(now);

		TableGroupGameResponseDto response = service.project(game);

		assertThat(response.revision()).isEqualTo(7);
		assertThat(response.players()).filteredOn(player -> player.userId().equals(first))
				.singleElement().extracting("hasActed").isEqualTo(true);
		assertThat(response.revealedActions()).hasSize(1);
		assertThat(response.revealedActions().getFirst().action()).isEqualTo(TableGroupGameActionType.ROCK);
	}

	private TableGroupGamePlayer player(UUID gameId, UUID userId, String username, Instant now) {
		return TableGroupGamePlayer.builder()
				.id(UUID.randomUUID())
				.gameId(gameId)
				.userId(userId)
				.username(username)
				.status(TableGroupGamePlayerStatus.ACTIVE)
				.joinedAt(now)
				.build();
	}

	private TableGroupGameAction action(
			UUID gameId,
			UUID actor,
			int round,
			boolean revealed,
			TableGroupGameActionType type
	) {
		return TableGroupGameAction.builder()
				.id(UUID.randomUUID())
				.gameId(gameId)
				.requestId(UUID.randomUUID())
				.roundNumber(round)
				.phase(TableGroupGamePhase.RPS)
				.actorUserId(actor)
				.action(type)
				.revealed(revealed)
				.build();
	}
}
