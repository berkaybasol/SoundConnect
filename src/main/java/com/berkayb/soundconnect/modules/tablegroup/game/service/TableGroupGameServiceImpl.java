package com.berkayb.soundconnect.modules.tablegroup.game.service;

import com.berkayb.soundconnect.modules.tablegroup.abuse.TableGroupRateLimitGuard;
import com.berkayb.soundconnect.modules.tablegroup.chat.entity.TableGroupMessage;
import com.berkayb.soundconnect.modules.tablegroup.chat.enums.MessageType;
import com.berkayb.soundconnect.modules.tablegroup.chat.dto.response.TableGroupMessageResponseDto;
import com.berkayb.soundconnect.modules.tablegroup.chat.mapper.TableGroupMessageMapper;
import com.berkayb.soundconnect.modules.tablegroup.chat.repository.TableGroupMessageRepository;
import com.berkayb.soundconnect.modules.tablegroup.entity.*;
import com.berkayb.soundconnect.modules.tablegroup.enums.*;
import com.berkayb.soundconnect.modules.tablegroup.game.dto.request.*;
import com.berkayb.soundconnect.modules.tablegroup.game.dto.response.TableGroupGameResponseDto;
import com.berkayb.soundconnect.modules.tablegroup.game.entity.*;
import com.berkayb.soundconnect.modules.tablegroup.game.enums.*;
import com.berkayb.soundconnect.modules.tablegroup.game.exception.TableGroupGameActiveExistsException;
import com.berkayb.soundconnect.modules.tablegroup.game.random.TableGroupDiceRoller;
import com.berkayb.soundconnect.modules.tablegroup.game.realtime.TableGroupGameRealtimePublisher;
import com.berkayb.soundconnect.modules.tablegroup.game.repository.*;
import com.berkayb.soundconnect.modules.tablegroup.game.support.TableGroupGameTimeProvider;
import com.berkayb.soundconnect.modules.tablegroup.game.support.TableGroupGameMentionFormatter;
import com.berkayb.soundconnect.modules.tablegroup.observability.TableGroupMetrics;
import com.berkayb.soundconnect.modules.tablegroup.repository.TableGroupRepository;
import com.berkayb.soundconnect.modules.tablegroup.support.TableGroupEntityFinder;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.shared.exception.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class TableGroupGameServiceImpl implements TableGroupGameService {
	static final Duration JOIN_DURATION = Duration.ofMinutes(3);
	static final Duration ACTION_DURATION = Duration.ofSeconds(20);
	static final int MAX_ROUNDS = 20;
	static final int DEADLINE_BATCH_SIZE = 100;
	private static final long MAX_MESSAGES_PER_TABLE_GROUP = 10_000L;
	private static final Set<TableGroupGameStatus> ACTIVE_STATUSES = EnumSet.of(
			TableGroupGameStatus.LOBBY,
			TableGroupGameStatus.IN_PROGRESS
	);

	private final TableGroupEntityFinder tableGroupEntityFinder;
	private final TableGroupRepository tableGroupRepository;
	private final TableGroupGameRepository gameRepository;
	private final TableGroupGamePlayerRepository playerRepository;
	private final TableGroupGameActionRepository actionRepository;
	private final TableGroupMessageRepository messageRepository;
	private final TableGroupMessageMapper messageMapper;
	private final UserRepository userRepository;
	private final TableGroupGameProjectionService projectionService;
	private final TableGroupGameRealtimePublisher realtimePublisher;
	private final TableGroupGameTimeProvider timeProvider;
	private final TableGroupDiceRoller diceRoller;
	private final TableGroupRateLimitGuard rateLimitGuard;
	private final TableGroupMetrics metrics;

	@Override
	@Transactional(noRollbackFor = TableGroupGameActiveExistsException.class)
	public TableGroupMessageResponseDto create(
			UUID requesterId,
			UUID tableGroupId,
			TableGroupGameCreateRequestDto request
	) {
		if (request == null || request.requestId() == null || request.mode() == null) {
			throw new SoundConnectException(ErrorType.VALIDATION_ERROR);
		}
		// Charge authenticated mutation attempts before taking the shared table
		// aggregate lock. Otherwise a throttled caller can still amplify database
		// contention even though the request will ultimately be rejected.
		rateLimitGuard.checkGameCreateUser(requesterId);
		if (hasOpenTableAccess(tableGroupId, requesterId, timeProvider.now())) {
			rateLimitGuard.checkGameCreateTable(tableGroupId);
		}
		LockedOpenTable lockedTable = lockOpenTableForAcceptedUser(tableGroupId, requesterId);
		TableGroup tableGroup = lockedTable.tableGroup();
		Instant now = lockedTable.now();

		Optional<TableGroupGame> replay = gameRepository
				.findByTableGroupIdAndCreatedByAndCreateRequestId(
						tableGroupId,
						requesterId,
						request.requestId()
				);
		if (replay.isPresent()) {
			if (replay.get().getMode() != request.mode()) {
				throw new SoundConnectException(ErrorType.TABLE_GROUP_GAME_ACTION_CONFLICT);
			}
			TableGroupGame lockedReplay = lockGame(tableGroupId, replay.get().getId());
			TableGroupMessageResponseDto reconciled = reconcileIfDue(tableGroup, lockedReplay, now);
			return reconciled == null ? messageResponse(lockedReplay) : reconciled;
		}
		Optional<TableGroupGame> activeGame = gameRepository
				.findFirstByTableGroupIdAndStatusInOrderByCreatedAtDesc(tableGroupId, ACTIVE_STATUSES);
		if (activeGame.isPresent()) {
			TableGroupGame lockedActiveGame = lockGame(tableGroupId, activeGame.get().getId());
			reconcileIfDue(tableGroup, lockedActiveGame, now);
			if (ACTIVE_STATUSES.contains(lockedActiveGame.getStatus())) {
				throw new TableGroupGameActiveExistsException();
			}
		}
		if (messageRepository.countByTableGroupIdAndDeletedAtIsNull(tableGroupId)
				>= MAX_MESSAGES_PER_TABLE_GROUP) {
			throw new SoundConnectException(ErrorType.TABLE_GROUP_CHAT_LIMIT_REACHED);
		}

		String username = requireUsername(requesterId);
		TableGroupGame game = gameRepository.save(TableGroupGame.builder()
				.version(0)
				.revision(1)
				.tableGroupId(tableGroupId)
				.createdBy(requesterId)
				.createdByUsername(username)
				.createRequestId(request.requestId())
				.topic(TableGroupGameTopic.WHO_PAYS)
				.mode(request.mode())
				.status(TableGroupGameStatus.LOBBY)
				.phase(TableGroupGamePhase.LOBBY)
				.roundNumber(0)
				.joinDeadlineAt(now.plus(JOIN_DURATION))
				.build());
		playerRepository.save(TableGroupGamePlayer.builder()
				.gameId(game.getId())
				.userId(requesterId)
				.username(username)
				.status(TableGroupGamePlayerStatus.ACTIVE)
				.joinedAt(now)
				.build());
		TableGroupMessage anchor = messageRepository.save(TableGroupMessage.builder()
				.tableGroupId(tableGroupId)
				.senderId(requesterId)
				.gameId(game.getId())
				.content("Hesap Kimde? oyunu basladi.")
				.messageType(MessageType.GAME)
				.deletedAt(null)
				.build());
		messageRepository.flush();

		TableGroupGameResponseDto response = projectionService.project(game);
		return realtimePublisher.publishCreatedAfterCommit(tableGroup, anchor, response);
	}

	@Override
	@Transactional
	public Optional<TableGroupMessageResponseDto> getActive(UUID requesterId, UUID tableGroupId) {
		rateLimitGuard.checkGameRead(requesterId);
		LockedOpenTable lockedTable = lockOpenTableForAcceptedUser(tableGroupId, requesterId);
		TableGroup tableGroup = lockedTable.tableGroup();
		Optional<TableGroupGame> active = gameRepository
				.findFirstByTableGroupIdAndStatusInOrderByCreatedAtDesc(tableGroupId, ACTIVE_STATUSES);
		if (active.isEmpty()) {
			return Optional.empty();
		}
		TableGroupGame game = lockGame(tableGroupId, active.get().getId());
		TableGroupMessageResponseDto reconciled = reconcileIfDue(tableGroup, game, lockedTable.now());
		return Optional.ofNullable(reconciled == null ? messageResponse(game) : reconciled);
	}

	@Override
	@Transactional
	public TableGroupMessageResponseDto get(UUID requesterId, UUID tableGroupId, UUID gameId) {
		rateLimitGuard.checkGameRead(requesterId);
		LockedOpenTable lockedTable = lockOpenTableForAcceptedUser(tableGroupId, requesterId);
		TableGroup tableGroup = lockedTable.tableGroup();
		TableGroupGame game = lockGame(tableGroupId, gameId);
		TableGroupMessageResponseDto reconciled = reconcileIfDue(tableGroup, game, lockedTable.now());
		return reconciled == null ? messageResponse(game) : reconciled;
	}

	@Override
	@Transactional
	public TableGroupMessageResponseDto join(UUID requesterId, UUID tableGroupId, UUID gameId) {
		checkGameCommandRateLimits(requesterId, tableGroupId, gameId);
		LockedOpenTable lockedTable = lockOpenTableForAcceptedUser(tableGroupId, requesterId);
		TableGroup tableGroup = lockedTable.tableGroup();
		TableGroupGame game = lockGame(tableGroupId, gameId);
		Instant now = lockedTable.now();
		Optional<TableGroupGamePlayer> existing = playerRepository.findByGameIdAndUserId(gameId, requesterId);
		TableGroupMessageResponseDto reconciled = reconcileIfDue(tableGroup, game, now);
		if (reconciled != null) {
			return reconciled;
		}
		if (existing.isPresent() && existing.get().getStatus() == TableGroupGamePlayerStatus.ACTIVE) {
			return messageResponse(game);
		}
		requireLobbyBeforeDeadline(game, now);
		if (existing.isPresent()) {
			TableGroupGamePlayer player = existing.get();
			player.setStatus(TableGroupGamePlayerStatus.ACTIVE);
			playerRepository.save(player);
		} else {
			playerRepository.save(TableGroupGamePlayer.builder()
					.gameId(gameId)
					.userId(requesterId)
					.username(requireUsername(requesterId))
					.status(TableGroupGamePlayerStatus.ACTIVE)
					.joinedAt(now)
					.build());
		}
		return persistAndPublish(game);
	}

	@Override
	@Transactional
	public TableGroupMessageResponseDto leave(UUID requesterId, UUID tableGroupId, UUID gameId) {
		checkGameCommandRateLimits(requesterId, tableGroupId, gameId);
		LockedOpenTable lockedTable = lockOpenTableForAcceptedUser(tableGroupId, requesterId);
		TableGroup tableGroup = lockedTable.tableGroup();
		TableGroupGame game = lockGame(tableGroupId, gameId);
		TableGroupGamePlayer player = requirePlayer(gameId, requesterId);
		Instant now = lockedTable.now();
		TableGroupMessageResponseDto reconciled = reconcileIfDue(tableGroup, game, now);
		if (reconciled != null) {
			return reconciled;
		}
		if (player.getStatus() == TableGroupGamePlayerStatus.LEFT) {
			return messageResponse(game);
		}
		requireLobbyBeforeDeadline(game, now);
		player.setStatus(TableGroupGamePlayerStatus.LEFT);
		playerRepository.save(player);
		if (game.getCreatedBy().equals(requesterId)) {
			cancelState(game, "CREATOR_LEFT", now);
		}
		return persistAndPublish(game);
	}

	@Override
	@Transactional
	public TableGroupMessageResponseDto start(UUID requesterId, UUID tableGroupId, UUID gameId) {
		checkGameCommandRateLimits(requesterId, tableGroupId, gameId);
		LockedOpenTable lockedTable = lockOpenTableForAcceptedUser(tableGroupId, requesterId);
		TableGroup tableGroup = lockedTable.tableGroup();
		TableGroupGame game = lockGame(tableGroupId, gameId);
		Instant now = lockedTable.now();
		if (!game.getCreatedBy().equals(requesterId)) {
			throw new SoundConnectException(ErrorType.FORBIDDEN_ACCESS);
		}
		TableGroupMessageResponseDto reconciled = reconcileIfDue(tableGroup, game, now);
		if (reconciled != null) {
			return reconciled;
		}
		if (game.getStatus() == TableGroupGameStatus.IN_PROGRESS) {
			return messageResponse(game);
		}
		requireLobbyBeforeDeadline(game, now);
		if (activePlayers(gameId).size() < 2) {
			throw new SoundConnectException(ErrorType.TABLE_GROUP_GAME_MIN_PLAYERS);
		}
		startState(game, now);
		metricAfterCommit(metrics::gameStarted);
		return persistAndPublish(game);
	}

	@Override
	@Transactional
	public TableGroupMessageResponseDto cancel(UUID requesterId, UUID tableGroupId, UUID gameId) {
		checkGameCommandRateLimits(requesterId, tableGroupId, gameId);
		LockedOpenTable lockedTable = lockOpenTableForAcceptedUser(tableGroupId, requesterId);
		TableGroup tableGroup = lockedTable.tableGroup();
		TableGroupGame game = lockGame(tableGroupId, gameId);
		if (!game.getCreatedBy().equals(requesterId)
				&& !tableGroup.getOwnerId().equals(requesterId)) {
			throw new SoundConnectException(ErrorType.FORBIDDEN_ACCESS);
		}
		Instant now = lockedTable.now();
		TableGroupMessageResponseDto reconciled = reconcileIfDue(tableGroup, game, now);
		if (reconciled != null) {
			return reconciled;
		}
		if (!ACTIVE_STATUSES.contains(game.getStatus())) {
			return messageResponse(game);
		}
		cancelState(
				game,
				game.getCreatedBy().equals(requesterId)
						? "CANCELLED_BY_CREATOR"
						: "CANCELLED_BY_OWNER",
				now
		);
		return persistAndPublish(game);
	}

	@Override
	@Transactional
	public TableGroupMessageResponseDto submitAction(
			UUID requesterId,
			UUID tableGroupId,
			UUID gameId,
			TableGroupGameActionRequestDto request
	) {
		if (request == null || request.requestId() == null || request.action() == null) {
			throw new SoundConnectException(ErrorType.VALIDATION_ERROR);
		}
		checkGameCommandRateLimits(requesterId, tableGroupId, gameId);
		LockedOpenTable lockedTable = lockOpenTableForAcceptedUser(tableGroupId, requesterId);
		TableGroup tableGroup = lockedTable.tableGroup();
		TableGroupGame game = lockGame(tableGroupId, gameId);
		TableGroupGamePlayer player = requirePlayer(gameId, requesterId);

		Optional<TableGroupGameAction> replay = actionRepository
				.findByGameIdAndActorUserIdAndRequestId(gameId, requesterId, request.requestId());
		if (replay.isPresent()) {
			if (!matchesRequest(replay.get(), requesterId, request)) {
				throw new SoundConnectException(ErrorType.TABLE_GROUP_GAME_ACTION_CONFLICT);
			}
		}

		Instant now = lockedTable.now();
		TableGroupMessageResponseDto reconciled = reconcileIfDue(tableGroup, game, now);
		if (reconciled != null) {
			return reconciled;
		}
		if (replay.isPresent()) {
			return messageResponse(game);
		}
		requireActionWindow(game, now);
		if (player.getStatus() != TableGroupGamePlayerStatus.ACTIVE) {
			throw new SoundConnectException(ErrorType.TABLE_GROUP_GAME_NOT_PLAYER);
		}
		if (actionRepository.findByGameIdAndRoundNumberAndActorUserId(
				gameId, game.getRoundNumber(), requesterId).isPresent()) {
			throw new SoundConnectException(ErrorType.TABLE_GROUP_GAME_ACTION_CONFLICT);
		}

		ValidatedAction validated = validateAction(game, requesterId, request);
		actionRepository.save(TableGroupGameAction.builder()
				.gameId(gameId)
				.requestId(request.requestId())
				.roundNumber(game.getRoundNumber())
				.phase(game.getPhase())
				.actorUserId(requesterId)
				.action(request.action())
				.targetUserId(validated.targetUserId())
				.value(validated.value())
				.revealed(false)
				.build());
		metricAfterCommit(metrics::gameActionSubmitted);

		if (allActivePlayersActed(game)) {
			resolveCurrentRound(game, now, false);
		}
		return persistAndPublish(game);
	}

	@Transactional
	public void advanceDeadline(UUID gameId) {
		TableGroupGame candidate = gameRepository.findById(gameId).orElse(null);
		if (candidate == null || !ACTIVE_STATUSES.contains(candidate.getStatus())) {
			return;
		}
		// Every path takes the aggregate locks in the same order.
		TableGroup tableGroup = tableGroupEntityFinder.getTableGroupByIdForUpdate(candidate.getTableGroupId());
		TableGroupGame game = lockGame(candidate.getTableGroupId(), gameId);
		reconcileIfDue(tableGroup, game, timeProvider.now());
	}

	@Transactional(readOnly = true)
	public List<UUID> findDueIds(int pageNumber) {
		return gameRepository.findDueIds(
				TableGroupGameStatus.LOBBY,
				TableGroupGameStatus.IN_PROGRESS,
				timeProvider.now(),
				PageRequest.of(Math.max(0, pageNumber), DEADLINE_BATCH_SIZE)
		);
	}

	@Transactional
	public void removeTableParticipantLocked(TableGroup tableGroup, UUID userId, String reason) {
		Optional<TableGroupGame> active = gameRepository
				.findFirstByTableGroupIdAndStatusInOrderByCreatedAtDesc(tableGroup.getId(), ACTIVE_STATUSES);
		if (active.isEmpty()) {
			return;
		}
		TableGroupGame game = lockGame(tableGroup.getId(), active.get().getId());
		Optional<TableGroupGamePlayer> optionalPlayer = playerRepository.findByGameIdAndUserId(game.getId(), userId);
		if (optionalPlayer.isEmpty()
				|| optionalPlayer.get().getStatus() == TableGroupGamePlayerStatus.LEFT
				|| optionalPlayer.get().getStatus() == TableGroupGamePlayerStatus.TIMED_OUT) {
			return;
		}
		TableGroupGamePlayer player = optionalPlayer.get();
		player.setStatus(TableGroupGamePlayerStatus.LEFT);
		playerRepository.save(player);
		Instant now = timeProvider.now();
		if (game.getStatus() == TableGroupGameStatus.LOBBY && game.getCreatedBy().equals(userId)) {
			cancelState(game, "CREATOR_LEFT", now);
		} else if (game.getStatus() == TableGroupGameStatus.IN_PROGRESS) {
			if (activePlayers(game.getId()).size() < 2) {
				cancelState(game, reason, now);
			} else if (allActivePlayersActed(game)) {
				resolveCurrentRound(game, now, false);
			}
		}
		persistAndPublish(game);
	}

	@Transactional
	public void closeActiveGameLocked(TableGroup tableGroup, String reason) {
		Optional<TableGroupGame> active = gameRepository
				.findFirstByTableGroupIdAndStatusInOrderByCreatedAtDesc(tableGroup.getId(), ACTIVE_STATUSES);
		if (active.isEmpty()) {
			return;
		}
		TableGroupGame game = lockGame(tableGroup.getId(), active.get().getId());
		cancelState(game, reason, timeProvider.now());
		persistAndPublish(game);
	}

	@Transactional
	public int purgeGamesForTableGroups(Collection<UUID> tableGroupIds) {
		if (tableGroupIds == null || tableGroupIds.isEmpty()) {
			return 0;
		}
		List<UUID> gameIds = gameRepository.findIdsByTableGroupIdIn(tableGroupIds);
		if (gameIds.isEmpty()) {
			return 0;
		}
		actionRepository.deleteAllByGameIdIn(gameIds);
		playerRepository.deleteAllByGameIdIn(gameIds);
		return gameRepository.deleteAllByIdInBulk(gameIds);
	}

	void resolveCurrentRound(TableGroupGame game, Instant now, boolean deadlineElapsed) {
		List<TableGroupGamePlayer> active = activePlayers(game.getId());
		List<TableGroupGameAction> actions = actionRepository
				.findByGameIdAndRoundNumberOrderByCreatedAtAscIdAsc(game.getId(), game.getRoundNumber());
		if (deadlineElapsed) {
			Set<UUID> actors = actions.stream()
					.map(TableGroupGameAction::getActorUserId)
					.collect(Collectors.toSet());
			for (TableGroupGamePlayer player : active) {
				if (!actors.contains(player.getUserId())) {
					player.setStatus(TableGroupGamePlayerStatus.TIMED_OUT);
				}
			}
			playerRepository.saveAll(active);
			active = active.stream()
					.filter(player -> player.getStatus() == TableGroupGamePlayerStatus.ACTIVE)
					.toList();
		}
		for (TableGroupGameAction action : actions) {
			action.setRevealed(true);
		}
		actionRepository.saveAll(actions);

		if (active.size() < 2) {
			cancelState(game, "NOT_ENOUGH_PLAYERS", now);
			return;
		}

		switch (game.getPhase()) {
			case RPS -> resolveRps(game, active, actions, now);
			case DICE, VOTE_TIE_DICE -> resolveDice(game, active, actions, now);
			case VOTE -> resolveVote(game, active, actions, now);
			default -> throw new SoundConnectException(ErrorType.TABLE_GROUP_GAME_STATE_INVALID);
		}
	}

	private TableGroupMessageResponseDto reconcileIfDue(
			TableGroup tableGroup,
			TableGroupGame game,
			Instant now
	) {
		if (game.getStatus() == TableGroupGameStatus.LOBBY
				&& game.getJoinDeadlineAt() != null
				&& !game.getJoinDeadlineAt().isAfter(now)) {
			if (!isOpen(tableGroup, now)) {
				cancelState(game, "TABLE_EXPIRED", now);
			} else if (activePlayers(game.getId()).size() < 2) {
				cancelState(game, "LOBBY_EXPIRED_NOT_ENOUGH_PLAYERS", now);
			} else {
				startState(game, now);
				metricAfterCommit(metrics::gameStarted);
			}
			return persistAndPublish(game);
		}
		if (game.getStatus() == TableGroupGameStatus.IN_PROGRESS
				&& game.getActionDeadlineAt() != null
				&& !game.getActionDeadlineAt().isAfter(now)) {
			if (!isOpen(tableGroup, now)) {
				cancelState(game, "TABLE_EXPIRED", now);
			} else {
				resolveCurrentRound(game, now, true);
			}
			return persistAndPublish(game);
		}
		return null;
	}

	void resolveRps(
			TableGroupGame game,
			List<TableGroupGamePlayer> active,
			List<TableGroupGameAction> actions,
			Instant now
	) {
		Set<UUID> activeIds = active.stream()
				.map(TableGroupGamePlayer::getUserId)
				.collect(Collectors.toSet());
		Map<UUID, TableGroupGameActionType> choices = actions.stream()
				.filter(action -> activeIds.contains(action.getActorUserId()))
				.collect(Collectors.toMap(
						TableGroupGameAction::getActorUserId,
						TableGroupGameAction::getAction,
						(first, ignored) -> first
				));
		Set<TableGroupGameActionType> distinct = new HashSet<>(choices.values());
		if (distinct.size() == 1 || distinct.size() == 3) {
			openNextRoundOrCancel(game, TableGroupGamePhase.RPS, now);
			return;
		}
		TableGroupGameActionType winner = rpsWinner(distinct);
		for (TableGroupGamePlayer player : active) {
			if (choices.get(player.getUserId()) == winner) {
				player.setStatus(TableGroupGamePlayerStatus.SAFE);
			}
		}
		playerRepository.saveAll(active);
		List<TableGroupGamePlayer> losers = active.stream()
				.filter(player -> player.getStatus() == TableGroupGamePlayerStatus.ACTIVE)
				.toList();
		if (losers.size() == 1) {
			completeState(game, losers.getFirst(), now);
		} else {
			openNextRoundOrCancel(game, TableGroupGamePhase.RPS, now);
		}
	}

	void resolveDice(
			TableGroupGame game,
			List<TableGroupGamePlayer> active,
			List<TableGroupGameAction> actions,
			Instant now
	) {
		Map<UUID, Integer> rolls = actions.stream()
				.collect(Collectors.toMap(
						TableGroupGameAction::getActorUserId,
						TableGroupGameAction::getValue,
						(first, ignored) -> first
				));
		int lowest = active.stream().map(TableGroupGamePlayer::getUserId)
				.map(rolls::get)
				.filter(Objects::nonNull)
				.min(Integer::compareTo)
				.orElseThrow(() -> new SoundConnectException(ErrorType.TABLE_GROUP_GAME_STATE_INVALID));
		for (TableGroupGamePlayer player : active) {
			if (!Objects.equals(rolls.get(player.getUserId()), lowest)) {
				player.setStatus(TableGroupGamePlayerStatus.SAFE);
			}
		}
		playerRepository.saveAll(active);
		List<TableGroupGamePlayer> tied = active.stream()
				.filter(player -> player.getStatus() == TableGroupGamePlayerStatus.ACTIVE)
				.toList();
		if (tied.size() == 1) {
			completeState(game, tied.getFirst(), now);
		} else {
			openNextRoundOrCancel(game, game.getPhase(), now);
		}
	}

	void resolveVote(
			TableGroupGame game,
			List<TableGroupGamePlayer> active,
			List<TableGroupGameAction> actions,
			Instant now
	) {
		Set<UUID> candidates = active.stream()
				.map(TableGroupGamePlayer::getUserId)
				.collect(Collectors.toCollection(LinkedHashSet::new));
		Map<UUID, Integer> votes = candidates.stream().collect(Collectors.toMap(
				Function.identity(),
				ignored -> 0,
				(first, ignored) -> first,
				LinkedHashMap::new
		));
		for (TableGroupGameAction action : actions) {
			if (candidates.contains(action.getActorUserId())
					&& candidates.contains(action.getTargetUserId())) {
				votes.computeIfPresent(action.getTargetUserId(), (ignored, count) -> count + 1);
			}
		}
		int maximum = votes.values().stream().max(Integer::compareTo).orElse(0);
		Set<UUID> tiedIds = votes.entrySet().stream()
				.filter(entry -> entry.getValue() == maximum)
				.map(Map.Entry::getKey)
				.collect(Collectors.toSet());
		for (TableGroupGamePlayer player : active) {
			if (!tiedIds.contains(player.getUserId())) {
				player.setStatus(TableGroupGamePlayerStatus.SAFE);
			}
		}
		playerRepository.saveAll(active);
		if (tiedIds.size() == 1) {
			TableGroupGamePlayer selected = active.stream()
					.filter(player -> tiedIds.contains(player.getUserId()))
					.findFirst()
					.orElseThrow();
			completeState(game, selected, now);
		} else {
			openNextRoundOrCancel(game, TableGroupGamePhase.VOTE_TIE_DICE, now);
		}
	}

	private TableGroupGameActionType rpsWinner(Set<TableGroupGameActionType> choices) {
		if (choices.contains(TableGroupGameActionType.ROCK)
				&& choices.contains(TableGroupGameActionType.SCISSORS)) {
			return TableGroupGameActionType.ROCK;
		}
		if (choices.contains(TableGroupGameActionType.SCISSORS)
				&& choices.contains(TableGroupGameActionType.PAPER)) {
			return TableGroupGameActionType.SCISSORS;
		}
		if (choices.contains(TableGroupGameActionType.PAPER)
				&& choices.contains(TableGroupGameActionType.ROCK)) {
			return TableGroupGameActionType.PAPER;
		}
		throw new SoundConnectException(ErrorType.TABLE_GROUP_GAME_STATE_INVALID);
	}

	private void openNextRoundOrCancel(
			TableGroupGame game,
			TableGroupGamePhase nextPhase,
			Instant now
	) {
		if (game.getRoundNumber() >= MAX_ROUNDS) {
			cancelState(game, "MAX_ROUNDS_REACHED", now);
			return;
		}
		game.setRoundNumber(game.getRoundNumber() + 1);
		game.setPhase(nextPhase);
		game.setActionDeadlineAt(now.plus(ACTION_DURATION));
	}

	private void completeState(TableGroupGame game, TableGroupGamePlayer selected, Instant now) {
		boolean volunteer = actionRepository.existsByGameIdAndActorUserIdAndAction(
				game.getId(), selected.getUserId(), TableGroupGameActionType.VOLUNTEER);
		game.setStatus(TableGroupGameStatus.COMPLETED);
		game.setPhase(TableGroupGamePhase.COMPLETED);
		game.setJoinDeadlineAt(null);
		game.setActionDeadlineAt(null);
		game.setCompletedAt(now);
		game.setSelectedUserId(selected.getUserId());
		game.setSelectedUsername(selected.getUsername());
		game.setOutcome(volunteer ? TableGroupGameOutcome.VOLUNTEER : TableGroupGameOutcome.ASSIGNED);
		String selectedMention = TableGroupGameMentionFormatter.mention(selected.getUsername());
		game.setResultMessage(volunteer
				? "SoundConnect ve masan, sadakatini takdir ediyor! " + selectedMention
						+ " hesabı gönüllü olarak üstlendi. 😎"
				: "Geçmiş olsun " + selectedMention
						+ "! Masan tarafından hesabı ödemekle cezalandırıldın. "
						+ "Umarız ipin ucu çok kaçmamıştır. 😄");
		game.setCancellationReason(null);
		metricAfterCommit(metrics::gameCompleted);
	}

	private void cancelState(TableGroupGame game, String reason, Instant now) {
		game.setStatus(TableGroupGameStatus.CANCELLED);
		game.setPhase(TableGroupGamePhase.CANCELLED);
		game.setJoinDeadlineAt(null);
		game.setActionDeadlineAt(null);
		game.setCompletedAt(now);
		game.setSelectedUserId(null);
		game.setSelectedUsername(null);
		game.setOutcome(null);
		game.setResultMessage(null);
		game.setCancellationReason(reason);
		metricAfterCommit(metrics::gameCancelled);
	}

	private void startState(TableGroupGame game, Instant now) {
		game.setStatus(TableGroupGameStatus.IN_PROGRESS);
		game.setPhase(switch (game.getMode()) {
			case ROCK_PAPER_SCISSORS -> TableGroupGamePhase.RPS;
			case DICE -> TableGroupGamePhase.DICE;
			case VOTE -> TableGroupGamePhase.VOTE;
		});
		game.setRoundNumber(1);
		game.setJoinDeadlineAt(null);
		game.setActionDeadlineAt(now.plus(ACTION_DURATION));
	}

	private TableGroupMessageResponseDto persistAndPublish(TableGroupGame game) {
		game.setRevision(game.getRevision() + 1);
		gameRepository.saveAndFlush(game);
		TableGroupMessage anchor = messageRepository.findByGameIdAndDeletedAtIsNull(game.getId())
				.orElseThrow(() -> new SoundConnectException(ErrorType.TABLE_GROUP_GAME_NOT_FOUND));
		if (game.getStatus() == TableGroupGameStatus.COMPLETED) {
			anchor.setContent(game.getResultMessage());
			messageRepository.saveAndFlush(anchor);
		} else if (game.getStatus() == TableGroupGameStatus.CANCELLED) {
			anchor.setContent("Hesap Kimde? oyunu iptal edildi.");
			messageRepository.saveAndFlush(anchor);
		}
		TableGroupGameResponseDto response = projectionService.project(game);
		return realtimePublisher.publishUpdatedAfterCommit(game.getTableGroupId(), anchor, response);
	}

	private TableGroupMessageResponseDto messageResponse(TableGroupGame game) {
		TableGroupMessage anchor = messageRepository.findByGameIdAndDeletedAtIsNull(game.getId())
				.orElseThrow(() -> new SoundConnectException(ErrorType.TABLE_GROUP_GAME_NOT_FOUND));
		return messageMapper.toResponseDto(anchor, projectionService.project(game));
	}

	private ValidatedAction validateAction(
			TableGroupGame game,
			UUID requesterId,
			TableGroupGameActionRequestDto request
	) {
		return switch (game.getPhase()) {
			case RPS -> {
				if (request.action() != TableGroupGameActionType.ROCK
						&& request.action() != TableGroupGameActionType.PAPER
						&& request.action() != TableGroupGameActionType.SCISSORS
						|| request.targetUserId() != null) {
					throw new SoundConnectException(ErrorType.VALIDATION_ERROR);
				}
				yield new ValidatedAction(null, null);
			}
			case DICE, VOTE_TIE_DICE -> {
				if (request.action() != TableGroupGameActionType.ROLL || request.targetUserId() != null) {
					throw new SoundConnectException(ErrorType.VALIDATION_ERROR);
				}
				yield new ValidatedAction(null, diceRoller.roll());
			}
			case VOTE -> {
				if (request.action() == TableGroupGameActionType.VOLUNTEER) {
					if (request.targetUserId() != null && !request.targetUserId().equals(requesterId)) {
						throw new SoundConnectException(ErrorType.VALIDATION_ERROR);
					}
					yield new ValidatedAction(requesterId, null);
				}
				if (request.action() != TableGroupGameActionType.VOTE
						|| request.targetUserId() == null) {
					throw new SoundConnectException(ErrorType.VALIDATION_ERROR);
				}
				TableGroupGamePlayer target = requirePlayer(game.getId(), request.targetUserId());
				if (target.getStatus() != TableGroupGamePlayerStatus.ACTIVE) {
					throw new SoundConnectException(ErrorType.VALIDATION_ERROR);
				}
				yield new ValidatedAction(request.targetUserId(), null);
			}
			default -> throw new SoundConnectException(ErrorType.TABLE_GROUP_GAME_STATE_INVALID);
		};
	}

	private boolean matchesRequest(
			TableGroupGameAction existing,
			UUID requesterId,
			TableGroupGameActionRequestDto request
	) {
		if (existing.getAction() != request.action()) {
			return false;
		}
		UUID requestedTarget = request.action() == TableGroupGameActionType.VOLUNTEER
				? requesterId
				: request.targetUserId();
		return Objects.equals(existing.getTargetUserId(), requestedTarget);
	}

	private boolean allActivePlayersActed(TableGroupGame game) {
		List<TableGroupGamePlayer> active = activePlayers(game.getId());
		if (active.isEmpty()) {
			return false;
		}
		Set<UUID> actors = actionRepository
				.findByGameIdAndRoundNumberOrderByCreatedAtAscIdAsc(game.getId(), game.getRoundNumber())
				.stream()
				.map(TableGroupGameAction::getActorUserId)
				.collect(Collectors.toSet());
		return active.stream().allMatch(player -> actors.contains(player.getUserId()));
	}

	private List<TableGroupGamePlayer> activePlayers(UUID gameId) {
		return playerRepository.findByGameIdAndStatusOrderByJoinedAtAscIdAsc(
				gameId, TableGroupGamePlayerStatus.ACTIVE);
	}

	private TableGroupGamePlayer requirePlayer(UUID gameId, UUID userId) {
		return playerRepository.findByGameIdAndUserId(gameId, userId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.TABLE_GROUP_GAME_NOT_PLAYER));
	}

	private TableGroupGame lockGame(UUID tableGroupId, UUID gameId) {
		return gameRepository.findByIdForUpdate(gameId)
				.filter(game -> game.getTableGroupId().equals(tableGroupId))
				.orElseThrow(() -> new SoundConnectException(ErrorType.TABLE_GROUP_GAME_NOT_FOUND));
	}

	private LockedOpenTable lockOpenTableForAcceptedUser(UUID tableGroupId, UUID userId) {
		TableGroup tableGroup = tableGroupEntityFinder.getTableGroupByIdForUpdate(tableGroupId);
		Instant now = timeProvider.now();
		requireOpenAndAccepted(tableGroup, userId, now);
		return new LockedOpenTable(tableGroup, now);
	}

	private void checkGameCommandRateLimits(UUID userId, UUID tableGroupId, UUID gameId) {
		rateLimitGuard.checkGameCommandUser(userId);
		Instant now = timeProvider.now();
		if (hasOpenTableAccess(tableGroupId, userId, now)
				&& gameRepository.findTableGroupIdById(gameId)
						.filter(tableGroupId::equals)
						.isPresent()) {
			rateLimitGuard.checkGameCommandGame(gameId);
		}
	}

	private boolean hasOpenTableAccess(UUID tableGroupId, UUID userId, Instant now) {
		return tableGroupRepository.countOpenAccess(
				tableGroupId,
				userId,
				TableGroupStatus.ACTIVE,
				ParticipantStatus.ACCEPTED,
				now
		) > 0;
	}

	private void requireOpenAndAccepted(TableGroup tableGroup, UUID userId, Instant now) {
		if (!isOpen(tableGroup, now)) {
			throw new SoundConnectException(ErrorType.TABLE_GROUP_NOT_FOUND);
		}
		boolean accepted = Optional.ofNullable(tableGroup.getParticipants()).orElseGet(Set::of)
				.stream()
				.anyMatch(participant -> Objects.equals(participant.getUserId(), userId)
						&& participant.getStatus() == ParticipantStatus.ACCEPTED);
		if (!accepted) {
			throw new SoundConnectException(ErrorType.FORBIDDEN_ACCESS);
		}
	}

	private boolean isOpen(TableGroup tableGroup, Instant now) {
		return tableGroup.getStatus() == TableGroupStatus.ACTIVE
				&& tableGroup.getExpiresAt() != null
				&& tableGroup.getExpiresAt().isAfter(now);
	}

	private void requireLobbyBeforeDeadline(TableGroupGame game, Instant now) {
		if (game.getStatus() != TableGroupGameStatus.LOBBY
				|| game.getPhase() != TableGroupGamePhase.LOBBY) {
			throw new SoundConnectException(ErrorType.TABLE_GROUP_GAME_STATE_INVALID);
		}
		if (game.getJoinDeadlineAt() == null || !game.getJoinDeadlineAt().isAfter(now)) {
			throw new SoundConnectException(ErrorType.TABLE_GROUP_GAME_DEADLINE_PASSED);
		}
	}

	private void requireActionWindow(TableGroupGame game, Instant now) {
		if (game.getStatus() != TableGroupGameStatus.IN_PROGRESS) {
			throw new SoundConnectException(ErrorType.TABLE_GROUP_GAME_STATE_INVALID);
		}
		if (game.getActionDeadlineAt() == null || !game.getActionDeadlineAt().isAfter(now)) {
			throw new SoundConnectException(ErrorType.TABLE_GROUP_GAME_DEADLINE_PASSED);
		}
	}

	private String requireUsername(UUID userId) {
		return userRepository.findById(userId)
				.map(User::getUsername)
				.filter(username -> !username.isBlank())
				.orElseThrow(() -> new SoundConnectException(ErrorType.USER_NOT_FOUND));
	}

	private void metricAfterCommit(Runnable metric) {
		Runnable safeMetric = () -> {
			try {
				metric.run();
			} catch (RuntimeException exception) {
				log.debug("Table-group game metric failed: {}", exception.toString());
			}
		};
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			safeMetric.run();
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				safeMetric.run();
			}
		});
	}

	private record ValidatedAction(UUID targetUserId, Integer value) {
	}

	private record LockedOpenTable(TableGroup tableGroup, Instant now) {
	}
}
