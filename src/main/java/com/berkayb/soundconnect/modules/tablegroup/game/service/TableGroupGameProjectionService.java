package com.berkayb.soundconnect.modules.tablegroup.game.service;

import com.berkayb.soundconnect.modules.profile.shared.identity.GhostListenerIdentity;
import com.berkayb.soundconnect.modules.profile.shared.identity.GhostListenerIdentityBatchResolver;
import com.berkayb.soundconnect.modules.tablegroup.game.dto.response.*;
import com.berkayb.soundconnect.modules.tablegroup.game.entity.*;
import com.berkayb.soundconnect.modules.tablegroup.game.repository.*;
import com.berkayb.soundconnect.modules.tablegroup.game.support.TableGroupGameMentionFormatter;
import com.berkayb.soundconnect.modules.tablegroup.game.support.TableGroupGameTimeProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TableGroupGameProjectionService {
	private static final int SCHEMA_VERSION = 1;

	private final TableGroupGameRepository gameRepository;
	private final TableGroupGamePlayerRepository playerRepository;
	private final TableGroupGameActionRepository actionRepository;
	private final TableGroupGameTimeProvider timeProvider;
	private final GhostListenerIdentityBatchResolver ghostIdentityBatchResolver;

	@Transactional
	public Optional<TableGroupGameResponseDto> find(UUID gameId) {
		return gameRepository.findById(gameId).map(this::project);
	}

	@Transactional
	public TableGroupGameResponseDto project(TableGroupGame game) {
		List<TableGroupGamePlayer> players = playerRepository
				.findByGameIdOrderByJoinedAtAscIdAsc(game.getId());
		List<TableGroupGameAction> actions = actionRepository
				.findByGameIdInOrderByGameIdAscRoundNumberAscCreatedAtAscIdAsc(List.of(game.getId()));
		Map<UUID, GhostListenerIdentity> ghostIdentities = resolveGhostIdentities(
				List.of(game),
				players
		);
		return project(game, players, actions, timeProvider.now(), ghostIdentities);
	}

	// Ghost identity resolution takes shared visibility locks. PostgreSQL does
	// not permit those locks in a read-only transaction.
	@Transactional(isolation = Isolation.REPEATABLE_READ)
	public Map<UUID, TableGroupGameResponseDto> projectByIds(Collection<UUID> gameIds) {
		if (gameIds == null || gameIds.isEmpty()) {
			return Map.of();
		}
		List<UUID> distinctIds = gameIds.stream().filter(Objects::nonNull).distinct().toList();
		if (distinctIds.isEmpty()) {
			return Map.of();
		}

		List<TableGroupGamePlayer> allPlayers = playerRepository
				.findByGameIdInOrderByGameIdAscJoinedAtAscIdAsc(distinctIds);
		Map<UUID, List<TableGroupGamePlayer>> playersByGame = allPlayers
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
		List<TableGroupGame> games = gameRepository.findByIdIn(distinctIds);
		Map<UUID, GhostListenerIdentity> ghostIdentities = resolveGhostIdentities(games, allPlayers);
		Instant now = timeProvider.now();
		return games.stream().collect(Collectors.toMap(
				TableGroupGame::getId,
				game -> project(
						game,
						playersByGame.getOrDefault(game.getId(), List.of()),
						actionsByGame.getOrDefault(game.getId(), List.of()),
						now,
						ghostIdentities
				),
				(first, ignored) -> first,
				LinkedHashMap::new
		));
	}

	private TableGroupGameResponseDto project(
			TableGroupGame game,
			List<TableGroupGamePlayer> players,
			List<TableGroupGameAction> actions,
			Instant now,
			Map<UUID, GhostListenerIdentity> ghostIdentities
	) {
		Set<UUID> actedCurrentRound = actions.stream()
				.filter(action -> action.getRoundNumber() == game.getRoundNumber())
				.map(TableGroupGameAction::getActorUserId)
				.collect(Collectors.toSet());
		List<TableGroupGamePlayerResponseDto> playerDtos = players.stream()
				.map(player -> {
					GhostListenerIdentity ghostIdentity = ghostIdentities.get(player.getUserId());
					return new TableGroupGamePlayerResponseDto(
							player.getUserId(),
							contextualUsername(player.getUsername(), ghostIdentity),
							player.getStatus(),
							player.getJoinedAt(),
							actedCurrentRound.contains(player.getUserId()),
							ghostIdentity == null ? null : ghostIdentity.visibilityMode()
					);
				})
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

		GhostListenerIdentity creatorIdentity = identityFor(ghostIdentities, game.getCreatedBy());
		GhostListenerIdentity selectedIdentity = identityFor(ghostIdentities, game.getSelectedUserId());
		String selectedUsername = contextualUsername(game.getSelectedUsername(), selectedIdentity);
		String resultMessage = selectedIdentity == null || game.getOutcome() == null
				? game.getResultMessage()
				: TableGroupGameMentionFormatter.resultMessage(game.getOutcome(), selectedUsername);

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
				contextualUsername(game.getCreatedByUsername(), creatorIdentity),
				creatorIdentity == null ? null : creatorIdentity.visibilityMode(),
				game.getRoundNumber(),
				game.getJoinDeadlineAt(),
				game.getActionDeadlineAt(),
				now,
				playerDtos,
				revealed,
				game.getSelectedUserId(),
				selectedUsername,
				selectedIdentity == null ? null : selectedIdentity.visibilityMode(),
				game.getOutcome(),
				resultMessage,
				game.getCancellationReason()
		);
	}

	private Map<UUID, GhostListenerIdentity> resolveGhostIdentities(
			Collection<TableGroupGame> games,
			Collection<TableGroupGamePlayer> players
	) {
		LinkedHashSet<UUID> userIds = new LinkedHashSet<>();
		if (games != null) {
			for (TableGroupGame game : games) {
				if (game == null) continue;
				if (game.getCreatedBy() != null) userIds.add(game.getCreatedBy());
				if (game.getSelectedUserId() != null) userIds.add(game.getSelectedUserId());
			}
		}
		if (players != null) {
			for (TableGroupGamePlayer player : players) {
				if (player != null && player.getUserId() != null) userIds.add(player.getUserId());
			}
		}
		if (userIds.isEmpty()) return Map.of();
		Map<UUID, GhostListenerIdentity> resolved = ghostIdentityBatchResolver.resolve(userIds);
		return resolved == null ? Map.of() : resolved;
	}

	private String contextualUsername(String storedUsername, GhostListenerIdentity ghostIdentity) {
		return ghostIdentity == null ? storedUsername : ghostIdentity.username();
	}

	private GhostListenerIdentity identityFor(
			Map<UUID, GhostListenerIdentity> identities,
			UUID userId
	) {
		return userId == null ? null : identities.get(userId);
	}
}
