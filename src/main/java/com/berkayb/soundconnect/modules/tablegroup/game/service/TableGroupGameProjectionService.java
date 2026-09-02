package com.berkayb.soundconnect.modules.tablegroup.game.service;

import com.berkayb.soundconnect.modules.tablegroup.game.dto.response.*;
import com.berkayb.soundconnect.modules.tablegroup.game.entity.*;
import com.berkayb.soundconnect.modules.tablegroup.game.repository.*;
import com.berkayb.soundconnect.modules.tablegroup.game.support.TableGroupGameTimeProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TableGroupGameProjectionService {
	private static final int SCHEMA_VERSION = 1;

	private final TableGroupGameRepository gameRepository;
	private final TableGroupGamePlayerRepository playerRepository;
	private final TableGroupGameActionRepository actionRepository;
	private final TableGroupGameTimeProvider timeProvider;

	@Transactional(readOnly = true)
	public Optional<TableGroupGameResponseDto> find(UUID gameId) {
		return gameRepository.findById(gameId).map(this::project);
	}

	@Transactional(readOnly = true)
	public TableGroupGameResponseDto project(TableGroupGame game) {
		List<TableGroupGamePlayer> players = playerRepository
				.findByGameIdOrderByJoinedAtAscIdAsc(game.getId());
		List<TableGroupGameAction> actions = actionRepository
				.findByGameIdInOrderByGameIdAscRoundNumberAscCreatedAtAscIdAsc(List.of(game.getId()));
		return project(game, players, actions, timeProvider.now());
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public Map<UUID, TableGroupGameResponseDto> projectByIds(Collection<UUID> gameIds) {
		if (gameIds == null || gameIds.isEmpty()) {
			return Map.of();
		}
		List<UUID> distinctIds = gameIds.stream().filter(Objects::nonNull).distinct().toList();
		if (distinctIds.isEmpty()) {
			return Map.of();
		}

		Map<UUID, List<TableGroupGamePlayer>> playersByGame = playerRepository
				.findByGameIdInOrderByGameIdAscJoinedAtAscIdAsc(distinctIds)
				.stream()
				.collect(Collectors.groupingBy(
						TableGroupGamePlayer::getGameId,
						LinkedHashMap::new,
						Collectors.toList()
				));
		Map<UUID, List<TableGroupGameAction>> actionsByGame = actionRepository
				.findByGameIdInOrderByGameIdAscRoundNumberAscCreatedAtAscIdAsc(distinctIds)
				.stream()
				.collect(Collectors.groupingBy(
						TableGroupGameAction::getGameId,
						LinkedHashMap::new,
						Collectors.toList()
				));
		Instant now = timeProvider.now();
		return gameRepository.findByIdIn(distinctIds).stream().collect(Collectors.toMap(
				TableGroupGame::getId,
				game -> project(
						game,
						playersByGame.getOrDefault(game.getId(), List.of()),
						actionsByGame.getOrDefault(game.getId(), List.of()),
						now
				),
				(first, ignored) -> first,
				LinkedHashMap::new
		));
	}

	private TableGroupGameResponseDto project(
			TableGroupGame game,
			List<TableGroupGamePlayer> players,
			List<TableGroupGameAction> actions,
			Instant now
	) {
		Set<UUID> actedCurrentRound = actions.stream()
				.filter(action -> action.getRoundNumber() == game.getRoundNumber())
				.map(TableGroupGameAction::getActorUserId)
				.collect(Collectors.toSet());
		List<TableGroupGamePlayerResponseDto> playerDtos = players.stream()
				.map(player -> new TableGroupGamePlayerResponseDto(
						player.getUserId(),
						player.getUsername(),
						player.getStatus(),
						player.getJoinedAt(),
						actedCurrentRound.contains(player.getUserId())
				))
				.toList();
		List<TableGroupGameRevealedActionResponseDto> revealed = actions.stream()
				.filter(TableGroupGameAction::isRevealed)
				.map(action -> new TableGroupGameRevealedActionResponseDto(
						action.getRoundNumber(),
						action.getPhase(),
						action.getActorUserId(),
						action.getAction(),
						action.getTargetUserId(),
						action.getValue()
				))
				.toList();

		return new TableGroupGameResponseDto(
				SCHEMA_VERSION,
				game.getId(),
				game.getTableGroupId(),
				game.getRevision(),
				game.getTopic(),
				game.getMode(),
				game.getStatus(),
				game.getPhase(),
				game.getCreatedBy(),
				game.getCreatedByUsername(),
				game.getRoundNumber(),
				game.getJoinDeadlineAt(),
				game.getActionDeadlineAt(),
				now,
				playerDtos,
				revealed,
				game.getSelectedUserId(),
				game.getSelectedUsername(),
				game.getOutcome(),
				game.getResultMessage(),
				game.getCancellationReason()
		);
	}
}
