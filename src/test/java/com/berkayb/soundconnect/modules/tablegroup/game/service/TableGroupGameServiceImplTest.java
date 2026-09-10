package com.berkayb.soundconnect.modules.tablegroup.game.service;

import com.berkayb.soundconnect.modules.tablegroup.abuse.TableGroupRateLimitGuard;
import com.berkayb.soundconnect.modules.tablegroup.chat.dto.response.TableGroupMessageResponseDto;
import com.berkayb.soundconnect.modules.tablegroup.chat.entity.TableGroupMessage;
import com.berkayb.soundconnect.modules.tablegroup.chat.enums.MessageType;
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
import com.berkayb.soundconnect.modules.tablegroup.observability.TableGroupMetrics;
import com.berkayb.soundconnect.modules.tablegroup.repository.TableGroupRepository;
import com.berkayb.soundconnect.modules.tablegroup.support.TableGroupEntityFinder;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.RateLimitedException;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TableGroupGameServiceImplTest {
	@Mock TableGroupEntityFinder tableGroupEntityFinder;
	@Mock TableGroupRepository tableGroupRepository;
	@Mock TableGroupGameRepository gameRepository;
	@Mock TableGroupGamePlayerRepository playerRepository;
	@Mock TableGroupGameActionRepository actionRepository;
	@Mock TableGroupMessageRepository messageRepository;
	@Mock TableGroupMessageMapper messageMapper;
	@Mock UserRepository userRepository;
	@Mock TableGroupGameProjectionService projectionService;
	@Mock TableGroupGameRealtimePublisher realtimePublisher;
	@Mock TableGroupGameTimeProvider timeProvider;
	@Mock TableGroupDiceRoller diceRoller;
	@Mock TableGroupRateLimitGuard rateLimitGuard;
	@Mock TableGroupMetrics metrics;
	@Mock TableGroupErasureGamePublisher erasurePublisher;
	@Mock com.berkayb.soundconnect.modules.user.support.AccountDeliveryFence accounts;
	@Mock com.berkayb.soundconnect.modules.notification.service.AfterCommitDeliveryExecutor erasureDelivery;
	@InjectMocks TableGroupGameServiceImpl service;

	@Test void erasedActorIsRejectedBeforeAnyGameAggregateLockOrPlayerInsertion() {
		UUID user=UUID.randomUUID(),table=UUID.randomUUID();
		doThrow(new SoundConnectException(ErrorType.ACCOUNT_DELETED)).when(accounts).requireActive(List.of(user));
		assertThatThrownBy(() -> service.create(user,table,new TableGroupGameCreateRequestDto(UUID.randomUUID(),TableGroupGameMode.DICE)))
				.isInstanceOfSatisfying(SoundConnectException.class,e -> assertThat(e.getErrorType()).isEqualTo(ErrorType.ACCOUNT_DELETED));
		verifyNoInteractions(tableGroupEntityFinder,gameRepository,playerRepository,messageRepository);
	}

	@ParameterizedTest
	@org.junit.jupiter.params.provider.CsvSource({"true,true", "true,false", "false,true", "false,false"})
	void accountErasurePersistsWithoutIdentityLocksAndQueuesOnlyAfterCommit(boolean owner, boolean committed) {
		Instant now = Instant.now(); UUID user = UUID.randomUUID(), tableId = UUID.randomUUID(), gameId = UUID.randomUUID();
		TableGroup table = openTable(tableId, user, now);
		TableGroupGame game = inProgressVote(gameId, tableId, user, now);
		TableGroupMessage anchor = TableGroupMessage.builder().id(UUID.randomUUID()).gameId(gameId).tableGroupId(tableId)
				.senderId(user).content("Previous identity-bearing game result").build();
		when(gameRepository.findFirstByTableGroupIdAndStatusInOrderByCreatedAtDesc(eq(tableId),any())).thenReturn(Optional.of(game));
		when(gameRepository.findByIdForUpdate(gameId)).thenReturn(Optional.of(game));
		when(timeProvider.now()).thenReturn(now);
		when(messageRepository.findByGameIdAndDeletedAtIsNull(gameId)).thenReturn(Optional.of(anchor));
		TableGroupGamePlayer player = player(gameId,user,"Old identity",now);
		if (!owner) when(playerRepository.findByGameIdAndUserId(gameId,user)).thenReturn(Optional.of(player));
		org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
		try {
			if (owner) service.closeForErasedOwnerLocked(table); else service.removeErasedAccountLocked(table,user);
			assertThat(game.getStatus()).isEqualTo(TableGroupGameStatus.CANCELLED);
			assertThat(anchor.getContent()).isEqualTo("Hesap Kimde? oyunu iptal edildi.");
			if (!owner) assertThat(player.getStatus()).isEqualTo(TableGroupGamePlayerStatus.LEFT);
			verify(gameRepository).saveAndFlush(game);
			verifyNoInteractions(projectionService,realtimePublisher,erasurePublisher,erasureDelivery);
			var callbacks = org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations();
			org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
			if (committed) callbacks.forEach(org.springframework.transaction.support.TransactionSynchronization::afterCommit);
			// A commit callback may still hold the original JDBC connection. It must
			// return after enqueueing, without invoking the REQUIRES_NEW worker.
			verifyNoInteractions(erasurePublisher);
			if (committed) {
				ArgumentCaptor<Runnable> queued = ArgumentCaptor.forClass(Runnable.class);
				verify(erasureDelivery).submit(queued.capture());
				queued.getValue().run();
				verify(erasurePublisher).refresh(gameId);
			} else verifyNoInteractions(erasureDelivery);
		} finally {
			if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive())
				org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
		}
	}

	@ParameterizedTest
	@org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
	void fullOrStoppingDeliveryQueueNeverRunsErasureProjectionInline(boolean stopping) throws Exception {
		var delivery = new com.berkayb.soundconnect.modules.notification.service.AfterCommitDeliveryExecutor();
		org.springframework.test.util.ReflectionTestUtils.setField(service, "erasureDelivery", delivery);
		var workerStarted = new java.util.concurrent.CountDownLatch(1);
		var releaseWorker = new java.util.concurrent.CountDownLatch(1);
		try {
			if (stopping) delivery.close();
			else {
				delivery.submit(() -> {
					workerStarted.countDown();
					try { releaseWorker.await(); }
					catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
				});
				assertThat(workerStarted.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
				for (int queued = 0; queued < 256; queued++) delivery.submit(() -> { });
			}
			Instant now = Instant.now(); UUID user = UUID.randomUUID(), tableId = UUID.randomUUID(), gameId = UUID.randomUUID();
			TableGroup table = openTable(tableId, user, now);
			TableGroupGame game = inProgressVote(gameId, tableId, user, now);
			TableGroupMessage anchor = TableGroupMessage.builder().id(UUID.randomUUID()).gameId(gameId).tableGroupId(tableId)
					.senderId(user).content("Old identity-bearing game result").build();
			when(gameRepository.findFirstByTableGroupIdAndStatusInOrderByCreatedAtDesc(eq(tableId), any())).thenReturn(Optional.of(game));
			when(gameRepository.findByIdForUpdate(gameId)).thenReturn(Optional.of(game));
			when(timeProvider.now()).thenReturn(now);
			when(messageRepository.findByGameIdAndDeletedAtIsNull(gameId)).thenReturn(Optional.of(anchor));
			org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
			service.closeForErasedOwnerLocked(table);
			var callbacks = org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations();
			org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
			assertThatCode(() -> callbacks.forEach(org.springframework.transaction.support.TransactionSynchronization::afterCommit))
					.doesNotThrowAnyException();
			assertThat(game.getStatus()).isEqualTo(TableGroupGameStatus.CANCELLED);
			assertThat(anchor.getContent()).isEqualTo("Hesap Kimde? oyunu iptal edildi.");
			verify(gameRepository).saveAndFlush(game);
			verifyNoInteractions(projectionService, realtimePublisher, erasurePublisher);
		} finally {
			if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive())
				org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
			releaseWorker.countDown();
			delivery.close();
		}
	}

	@Test
	void createPersistsOneGameAnchorAndAutoJoinsCreatorForThreeMinutes() {
		UUID tableId = UUID.randomUUID();
		UUID creatorId = UUID.randomUUID();
		UUID gameId = UUID.randomUUID();
		UUID messageId = UUID.randomUUID();
		Instant now = Instant.parse("2026-08-30T15:00:00Z");
		TableGroup table = openTable(tableId, creatorId, now);
		User creator = User.builder().id(creatorId).username("ece").build();
		TableGroupGameResponseDto gameResponse = mock(TableGroupGameResponseDto.class);
		TableGroupMessageResponseDto messageResponse = mock(TableGroupMessageResponseDto.class);

		when(timeProvider.now()).thenReturn(now);
		when(tableGroupEntityFinder.getTableGroupByIdForUpdate(tableId)).thenReturn(table);
		when(gameRepository.findByTableGroupIdAndCreatedByAndCreateRequestId(any(), any(), any()))
				.thenReturn(Optional.empty());
		when(gameRepository.findFirstByTableGroupIdAndStatusInOrderByCreatedAtDesc(any(), any()))
				.thenReturn(Optional.empty());
		when(messageRepository.countByTableGroupIdAndDeletedAtIsNull(tableId)).thenReturn(0L);
		when(userRepository.findById(creatorId)).thenReturn(Optional.of(creator));
		when(gameRepository.save(any())).thenAnswer(invocation -> {
			TableGroupGame game = invocation.getArgument(0);
			game.setId(gameId);
			return game;
		});
		when(playerRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
		when(messageRepository.save(any())).thenAnswer(invocation -> {
			TableGroupMessage message = invocation.getArgument(0);
			message.setId(messageId);
			return message;
		});
		when(projectionService.project(any(TableGroupGame.class))).thenReturn(gameResponse);
		when(realtimePublisher.publishCreatedAfterCommit(eq(table), any(), eq(gameResponse)))
				.thenReturn(messageResponse);

		var result = service.create(
				creatorId,
				tableId,
				new TableGroupGameCreateRequestDto(UUID.randomUUID(), TableGroupGameMode.VOTE)
		);

		assertThat(result).isSameAs(messageResponse);
		ArgumentCaptor<TableGroupGame> gameCaptor = ArgumentCaptor.forClass(TableGroupGame.class);
		verify(gameRepository).save(gameCaptor.capture());
		assertThat(gameCaptor.getValue().getJoinDeadlineAt()).isEqualTo(now.plusSeconds(180));
		assertThat(gameCaptor.getValue().getRevision()).isEqualTo(1);
		ArgumentCaptor<TableGroupGamePlayer> playerCaptor = ArgumentCaptor.forClass(TableGroupGamePlayer.class);
		verify(playerRepository).save(playerCaptor.capture());
		assertThat(playerCaptor.getValue().getUserId()).isEqualTo(creatorId);
		assertThat(playerCaptor.getValue().getStatus()).isEqualTo(TableGroupGamePlayerStatus.ACTIVE);
		ArgumentCaptor<TableGroupMessage> messageCaptor = ArgumentCaptor.forClass(TableGroupMessage.class);
		verify(messageRepository).save(messageCaptor.capture());
		assertThat(messageCaptor.getValue().getMessageType()).isEqualTo(MessageType.GAME);
		assertThat(messageCaptor.getValue().getGameId()).isEqualTo(gameId);
		InOrder actorOrder = inOrder(accounts, tableGroupEntityFinder, playerRepository, messageRepository);
		actorOrder.verify(accounts).requireActive(List.of(creatorId));
		actorOrder.verify(tableGroupEntityFinder).getTableGroupByIdForUpdate(tableId);
		actorOrder.verify(playerRepository).save(any(TableGroupGamePlayer.class));
		actorOrder.verify(messageRepository).save(any(TableGroupMessage.class));
		verify(timeProvider, times(2)).now();
	}

	@Test
	void createChargesSharedBucketAfterScalarAccessButBeforeAggregateLock() {
		UUID tableId = UUID.randomUUID();
		UUID userId = UUID.randomUUID();
		Instant now = Instant.parse("2026-08-30T15:00:00Z");
		TableGroup expired = openTable(tableId, userId, now);
		expired.setExpiresAt(now);
		when(timeProvider.now()).thenReturn(now);
		when(tableGroupRepository.countOpenAccess(
				eq(tableId), eq(userId), eq(TableGroupStatus.ACTIVE),
				eq(ParticipantStatus.ACCEPTED), eq(now))).thenReturn(1L);
		when(tableGroupEntityFinder.getTableGroupByIdForUpdate(tableId)).thenReturn(expired);

		assertThatThrownBy(() -> service.create(
				userId,
				tableId,
				new TableGroupGameCreateRequestDto(UUID.randomUUID(), TableGroupGameMode.DICE)
		)).isInstanceOf(SoundConnectException.class)
				.hasFieldOrPropertyWithValue("errorType", ErrorType.TABLE_GROUP_NOT_FOUND);

		InOrder order = inOrder(rateLimitGuard, tableGroupRepository, tableGroupEntityFinder);
		order.verify(rateLimitGuard).checkGameCreateUser(userId);
		order.verify(tableGroupRepository).countOpenAccess(
				tableId, userId, TableGroupStatus.ACTIVE, ParticipantStatus.ACCEPTED, now);
		order.verify(rateLimitGuard).checkGameCreateTable(tableId);
		order.verify(tableGroupEntityFinder).getTableGroupByIdForUpdate(tableId);
	}

	@Test
	void createWhenSharedBucketFailsDoesNotTakeAggregateLock() {
		UUID tableId = UUID.randomUUID();
		UUID userId = UUID.randomUUID();
		Instant now = Instant.parse("2026-08-30T15:00:00Z");
		when(timeProvider.now()).thenReturn(now);
		when(tableGroupRepository.countOpenAccess(
				eq(tableId), eq(userId), eq(TableGroupStatus.ACTIVE),
				eq(ParticipantStatus.ACCEPTED), eq(now))).thenReturn(1L);
		doThrow(new SoundConnectException(ErrorType.TABLE_GROUP_RATE_LIMIT_UNAVAILABLE))
				.when(rateLimitGuard).checkGameCreateTable(tableId);

		assertThatThrownBy(() -> service.create(
				userId,
				tableId,
				new TableGroupGameCreateRequestDto(UUID.randomUUID(), TableGroupGameMode.DICE)
		)).isInstanceOf(SoundConnectException.class)
				.hasFieldOrPropertyWithValue(
						"errorType", ErrorType.TABLE_GROUP_RATE_LIMIT_UNAVAILABLE);

		verifyNoInteractions(tableGroupEntityFinder);
		verifyNoInteractions(gameRepository);
	}

	@Test
	void commandForWrongTableDoesNotChargeSharedGameBucketAndStillFailsAuthoritativeCheck() {
		UUID requestedTableId = UUID.randomUUID();
		UUID actualTableId = UUID.randomUUID();
		UUID userId = UUID.randomUUID();
		UUID gameId = UUID.randomUUID();
		Instant now = Instant.parse("2026-08-30T15:00:00Z");
		TableGroupGame wrongTableGame = roundGame(gameId, TableGroupGamePhase.DICE, 1, now);
		wrongTableGame.setTableGroupId(actualTableId);
		when(timeProvider.now()).thenReturn(now);
		when(tableGroupRepository.countOpenAccess(
				eq(requestedTableId), eq(userId), eq(TableGroupStatus.ACTIVE),
				eq(ParticipantStatus.ACCEPTED), eq(now))).thenReturn(1L);
		when(gameRepository.findTableGroupIdById(gameId)).thenReturn(Optional.of(actualTableId));
		when(tableGroupEntityFinder.getTableGroupByIdForUpdate(requestedTableId))
				.thenReturn(openTable(requestedTableId, userId, now));
		when(gameRepository.findByIdForUpdate(gameId)).thenReturn(Optional.of(wrongTableGame));

		assertThatThrownBy(() -> service.start(userId, requestedTableId, gameId))
				.isInstanceOf(SoundConnectException.class)
				.hasFieldOrPropertyWithValue("errorType", ErrorType.TABLE_GROUP_GAME_NOT_FOUND);

		verify(rateLimitGuard, never()).checkGameCommandGame(any());
		verify(gameRepository).findByIdForUpdate(gameId);
	}

	@Test
	void commandWhenSharedBucketFailsDoesNotTakeAggregateOrGameLock() {
		UUID tableId = UUID.randomUUID();
		UUID userId = UUID.randomUUID();
		UUID gameId = UUID.randomUUID();
		Instant now = Instant.parse("2026-08-30T15:00:00Z");
		when(timeProvider.now()).thenReturn(now);
		when(tableGroupRepository.countOpenAccess(
				eq(tableId), eq(userId), eq(TableGroupStatus.ACTIVE),
				eq(ParticipantStatus.ACCEPTED), eq(now))).thenReturn(1L);
		when(gameRepository.findTableGroupIdById(gameId)).thenReturn(Optional.of(tableId));
		doThrow(new SoundConnectException(ErrorType.TABLE_GROUP_RATE_LIMIT_UNAVAILABLE))
				.when(rateLimitGuard).checkGameCommandGame(gameId);

		assertThatThrownBy(() -> service.start(userId, tableId, gameId))
				.isInstanceOf(SoundConnectException.class)
				.hasFieldOrPropertyWithValue(
						"errorType", ErrorType.TABLE_GROUP_RATE_LIMIT_UNAVAILABLE);

		verifyNoInteractions(tableGroupEntityFinder);
		verify(gameRepository, never()).findByIdForUpdate(any());
	}

	@Test
	void commandRechecksAccessUnderLockAfterSuccessfulPreflight() {
		UUID tableId = UUID.randomUUID();
		UUID userId = UUID.randomUUID();
		UUID gameId = UUID.randomUUID();
		Instant now = Instant.parse("2026-08-30T15:00:00Z");
		TableGroup lockedTable = openTable(tableId, UUID.randomUUID(), now);
		when(timeProvider.now()).thenReturn(now);
		when(tableGroupRepository.countOpenAccess(
				eq(tableId), eq(userId), eq(TableGroupStatus.ACTIVE),
				eq(ParticipantStatus.ACCEPTED), eq(now))).thenReturn(1L);
		when(gameRepository.findTableGroupIdById(gameId)).thenReturn(Optional.of(tableId));
		when(tableGroupEntityFinder.getTableGroupByIdForUpdate(tableId)).thenReturn(lockedTable);

		assertThatThrownBy(() -> service.start(userId, tableId, gameId))
				.isInstanceOf(SoundConnectException.class)
				.hasFieldOrPropertyWithValue("errorType", ErrorType.FORBIDDEN_ACCESS);

		verify(rateLimitGuard).checkGameCommandGame(gameId);
		verify(gameRepository, never()).findByIdForUpdate(any());
	}

	@Test
	void createRejectsTheExactTableExpiryBoundaryAfterRateLimitButBeforeGameQueries() {
		UUID tableId = UUID.randomUUID();
		UUID userId = UUID.randomUUID();
		Instant expiry = Instant.parse("2026-08-30T15:00:00Z");
		TableGroup table = openTable(tableId, userId, expiry);
		table.setExpiresAt(expiry);
		when(timeProvider.now()).thenReturn(expiry);
		when(tableGroupEntityFinder.getTableGroupByIdForUpdate(tableId)).thenReturn(table);

		assertThatThrownBy(() -> service.create(
				userId,
				tableId,
				new TableGroupGameCreateRequestDto(UUID.randomUUID(), TableGroupGameMode.DICE)
		)).isInstanceOf(SoundConnectException.class)
				.hasFieldOrPropertyWithValue("errorType", ErrorType.TABLE_GROUP_NOT_FOUND);
		verifyNoInteractions(gameRepository);
		verify(rateLimitGuard).checkGameCreateUser(userId);
	}

	@Test
	void malformedMutationBodiesFailValidationWithoutConsumingRateLimitOrTakingLocks() {
		UUID userId = UUID.randomUUID();
		UUID tableId = UUID.randomUUID();
		UUID gameId = UUID.randomUUID();

		assertValidationError(() -> service.create(userId, tableId, null));
		assertValidationError(() -> service.create(
				userId,
				tableId,
				new TableGroupGameCreateRequestDto(null, TableGroupGameMode.DICE)
		));
		assertValidationError(() -> service.create(
				userId,
				tableId,
				new TableGroupGameCreateRequestDto(UUID.randomUUID(), null)
		));
		assertValidationError(() -> service.submitAction(userId, tableId, gameId, null));
		assertValidationError(() -> service.submitAction(
				userId,
				tableId,
				gameId,
				new TableGroupGameActionRequestDto(null, TableGroupGameActionType.ROCK, null)
		));
		assertValidationError(() -> service.submitAction(
				userId,
				tableId,
				gameId,
				new TableGroupGameActionRequestDto(UUID.randomUUID(), null, null)
		));

		verifyNoInteractions(
				rateLimitGuard,
				tableGroupEntityFinder,
				gameRepository,
				playerRepository,
				actionRepository,
				messageRepository,
				userRepository,
				timeProvider
		);
	}

	@ParameterizedTest
	@EnumSource(GameMutation.class)
	void rateLimitedMutationsNeverTakeAggregateOrGameLocks(GameMutation mutation) {
		UUID userId = UUID.randomUUID();
		UUID tableId = UUID.randomUUID();
		UUID gameId = UUID.randomUUID();
		RateLimitedException rejection = new RateLimitedException(
				ErrorType.TABLE_GROUP_RATE_LIMITED,
				1L
		);
		if (mutation == GameMutation.CREATE) {
			doThrow(rejection).when(rateLimitGuard).checkGameCreateUser(userId);
		} else {
			doThrow(rejection).when(rateLimitGuard).checkGameCommandUser(userId);
		}

		assertThatThrownBy(() -> invokeMutation(mutation, userId, tableId, gameId))
				.isSameAs(rejection);

		if (mutation == GameMutation.CREATE) {
			verify(rateLimitGuard).checkGameCreateUser(userId);
		} else {
			verify(rateLimitGuard).checkGameCommandUser(userId);
		}
		verifyNoInteractions(
				tableGroupEntityFinder,
				gameRepository,
				playerRepository,
				actionRepository,
				messageRepository,
				userRepository,
				timeProvider,
				projectionService,
				realtimePublisher
		);
	}

	@Test
	void voteAcceptsOrdinarySelfVoteWithoutTreatingItAsVolunteer() {
		UUID tableId = UUID.randomUUID();
		UUID userId = UUID.randomUUID();
		UUID otherId = UUID.randomUUID();
		UUID gameId = UUID.randomUUID();
		Instant now = Instant.parse("2026-08-30T15:00:00Z");
		TableGroup table = openTable(tableId, userId, now);
		TableGroupGame game = inProgressVote(gameId, tableId, userId, now);
		TableGroupGamePlayer player = player(gameId, userId, "ece", now);
		TableGroupGamePlayer other = player(gameId, otherId, "mert", now);
		TableGroupMessage anchor = TableGroupMessage.builder()
				.id(UUID.randomUUID()).tableGroupId(tableId).senderId(userId)
				.gameId(gameId).content("Hesap Kimde? oyunu basladi.")
				.messageType(MessageType.GAME).build();
		TableGroupMessageResponseDto response = mock(TableGroupMessageResponseDto.class);
		when(timeProvider.now()).thenReturn(now);
		when(tableGroupEntityFinder.getTableGroupByIdForUpdate(tableId)).thenReturn(table);
		when(gameRepository.findByIdForUpdate(gameId)).thenReturn(Optional.of(game));
		when(actionRepository.findByGameIdAndActorUserIdAndRequestId(any(), any(), any()))
				.thenReturn(Optional.empty());
		when(playerRepository.findByGameIdAndUserId(gameId, userId)).thenReturn(Optional.of(player));
		when(actionRepository.findByGameIdAndRoundNumberAndActorUserId(gameId, 1, userId))
				.thenReturn(Optional.empty());
		when(actionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
		when(playerRepository.findByGameIdAndStatusOrderByJoinedAtAscIdAsc(
				gameId, TableGroupGamePlayerStatus.ACTIVE)).thenReturn(List.of(player, other));
		when(actionRepository.findByGameIdAndRoundNumberOrderByCreatedAtAscIdAsc(gameId, 1))
				.thenAnswer(ignored -> List.of());
		when(messageRepository.findByGameIdAndDeletedAtIsNull(gameId)).thenReturn(Optional.of(anchor));
		when(realtimePublisher.publishUpdatedAfterCommit(eq(tableId), eq(anchor), any()))
				.thenReturn(response);

		assertThat(service.submitAction(
				userId,
				tableId,
				gameId,
				new TableGroupGameActionRequestDto(
						UUID.randomUUID(), TableGroupGameActionType.VOTE, userId)
		)).isSameAs(response);
		ArgumentCaptor<TableGroupGameAction> actionCaptor = ArgumentCaptor.forClass(TableGroupGameAction.class);
		verify(actionRepository).save(actionCaptor.capture());
		assertThat(actionCaptor.getValue().getAction()).isEqualTo(TableGroupGameActionType.VOTE);
		assertThat(actionCaptor.getValue().getTargetUserId()).isEqualTo(userId);
		assertThat(game.getOutcome()).isNull();
		verify(actionRepository, never()).existsByGameIdAndActorUserIdAndAction(
				any(), any(), eq(TableGroupGameActionType.VOLUNTEER));
	}

	@Test
	void getReconcilesAnExpiredLobbyBeforeReturningTheAnchor() {
		UUID tableId = UUID.randomUUID();
		UUID creatorId = UUID.randomUUID();
		UUID secondId = UUID.randomUUID();
		UUID gameId = UUID.randomUUID();
		Instant now = Instant.parse("2026-08-30T15:00:00Z");
		TableGroup table = openTable(tableId, creatorId, now);
		TableGroupGame game = TableGroupGame.builder()
				.id(gameId).version(0).revision(1).tableGroupId(tableId)
				.createdBy(creatorId).createdByUsername("ece").createRequestId(UUID.randomUUID())
				.topic(TableGroupGameTopic.WHO_PAYS).mode(TableGroupGameMode.DICE)
				.status(TableGroupGameStatus.LOBBY).phase(TableGroupGamePhase.LOBBY)
				.roundNumber(0).joinDeadlineAt(now).build();
		TableGroupMessage anchor = TableGroupMessage.builder()
				.id(UUID.randomUUID()).tableGroupId(tableId).senderId(creatorId)
				.gameId(gameId).content("Hesap Kimde? oyunu basladi.")
				.messageType(MessageType.GAME).build();
		TableGroupMessageResponseDto response = mock(TableGroupMessageResponseDto.class);
		when(timeProvider.now()).thenReturn(now);
		when(tableGroupEntityFinder.getTableGroupByIdForUpdate(tableId)).thenReturn(table);
		when(gameRepository.findByIdForUpdate(gameId)).thenReturn(Optional.of(game));
		when(playerRepository.findByGameIdAndStatusOrderByJoinedAtAscIdAsc(
				gameId, TableGroupGamePlayerStatus.ACTIVE)).thenReturn(List.of(
				player(gameId, creatorId, "ece", now),
				player(gameId, secondId, "mert", now)
		));
		when(messageRepository.findByGameIdAndDeletedAtIsNull(gameId)).thenReturn(Optional.of(anchor));
		when(realtimePublisher.publishUpdatedAfterCommit(eq(tableId), eq(anchor), any()))
				.thenReturn(response);

		assertThat(service.get(creatorId, tableId, gameId)).isSameAs(response);
		assertThat(game.getStatus()).isEqualTo(TableGroupGameStatus.IN_PROGRESS);
		assertThat(game.getPhase()).isEqualTo(TableGroupGamePhase.DICE);
		assertThat(game.getRoundNumber()).isEqualTo(1);
		assertThat(game.getRevision()).isEqualTo(2);
		assertThat(game.getActionDeadlineAt()).isEqualTo(now.plusSeconds(20));
		verify(rateLimitGuard).checkGameRead(creatorId);
	}

	@Test
	void getActiveRejectsRateLimitedRequesterBeforeTakingTheTableLock() {
		UUID tableId = UUID.randomUUID();
		UUID userId = UUID.randomUUID();
		doThrow(new SoundConnectException(ErrorType.TABLE_GROUP_RATE_LIMITED))
				.when(rateLimitGuard).checkGameRead(userId);

		assertThatThrownBy(() -> service.getActive(userId, tableId))
				.isInstanceOf(SoundConnectException.class)
				.hasFieldOrPropertyWithValue("errorType", ErrorType.TABLE_GROUP_RATE_LIMITED);

		verifyNoInteractions(tableGroupEntityFinder);
		verifyNoInteractions(gameRepository);
	}

	@Test
	void getRejectsRateLimitedRequesterBeforeTakingTheTableLock() {
		UUID tableId = UUID.randomUUID();
		UUID userId = UUID.randomUUID();
		UUID gameId = UUID.randomUUID();
		doThrow(new SoundConnectException(ErrorType.TABLE_GROUP_RATE_LIMITED))
				.when(rateLimitGuard).checkGameRead(userId);

		assertThatThrownBy(() -> service.get(userId, tableId, gameId))
				.isInstanceOf(SoundConnectException.class)
				.hasFieldOrPropertyWithValue("errorType", ErrorType.TABLE_GROUP_RATE_LIMITED);

		verifyNoInteractions(tableGroupEntityFinder);
		verifyNoInteractions(gameRepository);
	}

	@Test
	void selectedVolunteerGetsCanonicalHonourMessageAndAllVotesAreRevealed() {
		UUID tableId = UUID.randomUUID();
		UUID firstId = UUID.randomUUID();
		UUID volunteerId = UUID.randomUUID();
		UUID gameId = UUID.randomUUID();
		Instant now = Instant.parse("2026-08-30T15:00:00Z");
		TableGroup table = openTable(tableId, volunteerId, now);
		TableGroupGame game = inProgressVote(gameId, tableId, volunteerId, now);
		TableGroupGamePlayer first = player(gameId, firstId, "mert", now);
		TableGroupGamePlayer volunteer = player(gameId, volunteerId, "ece", now);
		TableGroupGameAction firstVote = TableGroupGameAction.builder()
				.id(UUID.randomUUID()).gameId(gameId).requestId(UUID.randomUUID())
				.roundNumber(1).phase(TableGroupGamePhase.VOTE).actorUserId(firstId)
				.action(TableGroupGameActionType.VOTE).targetUserId(volunteerId).revealed(false).build();
		List<TableGroupGameAction> actions = new ArrayList<>(List.of(firstVote));
		TableGroupMessage anchor = TableGroupMessage.builder()
				.id(UUID.randomUUID()).tableGroupId(tableId).senderId(volunteerId)
				.gameId(gameId).content("Hesap Kimde? oyunu basladi.")
				.messageType(MessageType.GAME).build();
		TableGroupMessageResponseDto response = mock(TableGroupMessageResponseDto.class);

		when(timeProvider.now()).thenReturn(now);
		when(tableGroupEntityFinder.getTableGroupByIdForUpdate(tableId)).thenReturn(table);
		when(gameRepository.findByIdForUpdate(gameId)).thenReturn(Optional.of(game));
		when(actionRepository.findByGameIdAndActorUserIdAndRequestId(any(), any(), any()))
				.thenReturn(Optional.empty());
		when(playerRepository.findByGameIdAndUserId(gameId, volunteerId)).thenReturn(Optional.of(volunteer));
		when(actionRepository.findByGameIdAndRoundNumberAndActorUserId(gameId, 1, volunteerId))
				.thenReturn(Optional.empty());
		when(playerRepository.findByGameIdAndStatusOrderByJoinedAtAscIdAsc(
				gameId, TableGroupGamePlayerStatus.ACTIVE)).thenReturn(List.of(first, volunteer));
		when(actionRepository.save(any())).thenAnswer(invocation -> {
			TableGroupGameAction saved = invocation.getArgument(0);
			actions.add(saved);
			return saved;
		});
		when(actionRepository.findByGameIdAndRoundNumberOrderByCreatedAtAscIdAsc(gameId, 1))
				.thenAnswer(ignored -> List.copyOf(actions));
		when(actionRepository.existsByGameIdAndActorUserIdAndAction(
				gameId, volunteerId, TableGroupGameActionType.VOLUNTEER)).thenReturn(true);
		when(messageRepository.findByGameIdAndDeletedAtIsNull(gameId)).thenReturn(Optional.of(anchor));
		when(realtimePublisher.publishUpdatedAfterCommit(eq(tableId), eq(anchor), any()))
				.thenReturn(response);

		var result = service.submitAction(
				volunteerId,
				tableId,
				gameId,
				new TableGroupGameActionRequestDto(
						UUID.randomUUID(), TableGroupGameActionType.VOLUNTEER, null)
		);

		assertThat(result).isSameAs(response);
		assertThat(game.getStatus()).isEqualTo(TableGroupGameStatus.COMPLETED);
		assertThat(game.getOutcome()).isEqualTo(TableGroupGameOutcome.VOLUNTEER);
		assertThat(game.getResultMessage()).isEqualTo(
				"SoundConnect ve masan, sadakatini takdir ediyor! @ece hesabı gönüllü olarak üstlendi. 😎");
		assertThat(actions).allMatch(TableGroupGameAction::isRevealed);
	}

	@Test
	void rpsEliminatesTheWinningGestureAndAssignsTheOnlyLoserWithCanonicalCopy() {
		UUID tableId = UUID.randomUUID();
		UUID rockId = UUID.randomUUID();
		UUID scissorsId = UUID.randomUUID();
		UUID gameId = UUID.randomUUID();
		Instant now = Instant.parse("2026-08-30T15:00:00Z");
		TableGroup table = openTable(tableId, scissorsId, now);
		TableGroupGame game = TableGroupGame.builder()
				.id(gameId).version(0).revision(4).tableGroupId(tableId)
				.createdBy(scissorsId).createdByUsername("mert").createRequestId(UUID.randomUUID())
				.topic(TableGroupGameTopic.WHO_PAYS).mode(TableGroupGameMode.ROCK_PAPER_SCISSORS)
				.status(TableGroupGameStatus.IN_PROGRESS).phase(TableGroupGamePhase.RPS)
				.roundNumber(1).actionDeadlineAt(now.plusSeconds(20)).build();
		TableGroupGamePlayer rock = player(gameId, rockId, "ece", now);
		TableGroupGamePlayer scissors = player(gameId, scissorsId, "mert", now);
		TableGroupGameAction rockAction = TableGroupGameAction.builder()
				.id(UUID.randomUUID()).gameId(gameId).requestId(UUID.randomUUID())
				.roundNumber(1).phase(TableGroupGamePhase.RPS).actorUserId(rockId)
				.action(TableGroupGameActionType.ROCK).revealed(false).build();
		List<TableGroupGameAction> actions = new ArrayList<>(List.of(rockAction));
		TableGroupMessage anchor = TableGroupMessage.builder()
				.id(UUID.randomUUID()).tableGroupId(tableId).senderId(scissorsId)
				.gameId(gameId).content("Hesap Kimde? oyunu basladi.")
				.messageType(MessageType.GAME).build();
		TableGroupMessageResponseDto response = mock(TableGroupMessageResponseDto.class);

		when(timeProvider.now()).thenReturn(now);
		when(tableGroupEntityFinder.getTableGroupByIdForUpdate(tableId)).thenReturn(table);
		when(gameRepository.findByIdForUpdate(gameId)).thenReturn(Optional.of(game));
		when(actionRepository.findByGameIdAndActorUserIdAndRequestId(any(), any(), any()))
				.thenReturn(Optional.empty());
		when(playerRepository.findByGameIdAndUserId(gameId, scissorsId)).thenReturn(Optional.of(scissors));
		when(actionRepository.findByGameIdAndRoundNumberAndActorUserId(gameId, 1, scissorsId))
				.thenReturn(Optional.empty());
		when(playerRepository.findByGameIdAndStatusOrderByJoinedAtAscIdAsc(
				gameId, TableGroupGamePlayerStatus.ACTIVE)).thenReturn(List.of(rock, scissors));
		when(actionRepository.save(any())).thenAnswer(invocation -> {
			TableGroupGameAction saved = invocation.getArgument(0);
			actions.add(saved);
			return saved;
		});
		when(actionRepository.findByGameIdAndRoundNumberOrderByCreatedAtAscIdAsc(gameId, 1))
				.thenAnswer(ignored -> List.copyOf(actions));
		when(messageRepository.findByGameIdAndDeletedAtIsNull(gameId)).thenReturn(Optional.of(anchor));
		when(realtimePublisher.publishUpdatedAfterCommit(eq(tableId), eq(anchor), any()))
				.thenReturn(response);

		service.submitAction(
				scissorsId,
				tableId,
				gameId,
				new TableGroupGameActionRequestDto(
						UUID.randomUUID(), TableGroupGameActionType.SCISSORS, null)
		);

		assertThat(rock.getStatus()).isEqualTo(TableGroupGamePlayerStatus.SAFE);
		assertThat(game.getSelectedUserId()).isEqualTo(scissorsId);
		assertThat(game.getOutcome()).isEqualTo(TableGroupGameOutcome.ASSIGNED);
		assertThat(game.getResultMessage()).isEqualTo(
				"Geçmiş olsun @mert! Masan tarafından hesabı ödemekle cezalandırıldın. "
						+ "Umarız ipin ucu çok kaçmamıştır. 😄");
	}

	@Test
	void rpsSameAndAllThreeChoicesOpenANewRoundWithoutEliminatingAnyone() {
		Instant now = Instant.parse("2026-08-30T15:00:00Z");
		UUID gameId = UUID.randomUUID();
		List<TableGroupGamePlayer> players = List.of(
				player(gameId, UUID.randomUUID(), "a", now),
				player(gameId, UUID.randomUUID(), "b", now),
				player(gameId, UUID.randomUUID(), "c", now)
		);
		TableGroupGame same = roundGame(gameId, TableGroupGamePhase.RPS, 1, now);
		service.resolveRps(same, players, List.of(
				action(gameId, players.get(0).getUserId(), 1, TableGroupGamePhase.RPS, TableGroupGameActionType.ROCK, null, null),
				action(gameId, players.get(1).getUserId(), 1, TableGroupGamePhase.RPS, TableGroupGameActionType.ROCK, null, null),
				action(gameId, players.get(2).getUserId(), 1, TableGroupGamePhase.RPS, TableGroupGameActionType.ROCK, null, null)
		), now);
		assertThat(same.getRoundNumber()).isEqualTo(2);
		assertThat(players).allMatch(player -> player.getStatus() == TableGroupGamePlayerStatus.ACTIVE);

		TableGroupGame allThree = roundGame(gameId, TableGroupGamePhase.RPS, 5, now);
		service.resolveRps(allThree, players, List.of(
				action(gameId, players.get(0).getUserId(), 5, TableGroupGamePhase.RPS, TableGroupGameActionType.ROCK, null, null),
				action(gameId, players.get(1).getUserId(), 5, TableGroupGamePhase.RPS, TableGroupGameActionType.PAPER, null, null),
				action(gameId, players.get(2).getUserId(), 5, TableGroupGamePhase.RPS, TableGroupGameActionType.SCISSORS, null, null)
		), now);
		assertThat(allThree.getRoundNumber()).isEqualTo(6);
		assertThat(players).allMatch(player -> player.getStatus() == TableGroupGamePlayerStatus.ACTIVE);
	}

	@Test
	void diceKeepsOnlyLowestTieActiveForInteractiveReroll() {
		Instant now = Instant.parse("2026-08-30T15:00:00Z");
		UUID gameId = UUID.randomUUID();
		TableGroupGame game = roundGame(gameId, TableGroupGamePhase.DICE, 1, now);
		TableGroupGamePlayer first = player(gameId, UUID.randomUUID(), "a", now);
		TableGroupGamePlayer second = player(gameId, UUID.randomUUID(), "b", now);
		TableGroupGamePlayer high = player(gameId, UUID.randomUUID(), "c", now);

		service.resolveDice(game, List.of(first, second, high), List.of(
				action(gameId, first.getUserId(), 1, TableGroupGamePhase.DICE, TableGroupGameActionType.ROLL, null, 1),
				action(gameId, second.getUserId(), 1, TableGroupGamePhase.DICE, TableGroupGameActionType.ROLL, null, 1),
				action(gameId, high.getUserId(), 1, TableGroupGamePhase.DICE, TableGroupGameActionType.ROLL, null, 6)
		), now);

		assertThat(first.getStatus()).isEqualTo(TableGroupGamePlayerStatus.ACTIVE);
		assertThat(second.getStatus()).isEqualTo(TableGroupGamePlayerStatus.ACTIVE);
		assertThat(high.getStatus()).isEqualTo(TableGroupGamePlayerStatus.SAFE);
		assertThat(game.getRoundNumber()).isEqualTo(2);
		assertThat(game.getPhase()).isEqualTo(TableGroupGamePhase.DICE);
	}

	@Test
	void diceAssignsTheOnlyLowestRoller() {
		Instant now = Instant.parse("2026-08-30T15:00:00Z");
		UUID gameId = UUID.randomUUID();
		TableGroupGame game = roundGame(gameId, TableGroupGamePhase.DICE, 1, now);
		TableGroupGamePlayer lowest = player(gameId, UUID.randomUUID(), "ece", now);
		TableGroupGamePlayer high = player(gameId, UUID.randomUUID(), "mert", now);

		service.resolveDice(game, List.of(lowest, high), List.of(
				action(gameId, lowest.getUserId(), 1, TableGroupGamePhase.DICE, TableGroupGameActionType.ROLL, null, 1),
				action(gameId, high.getUserId(), 1, TableGroupGamePhase.DICE, TableGroupGameActionType.ROLL, null, 6)
		), now);

		assertThat(game.getStatus()).isEqualTo(TableGroupGameStatus.COMPLETED);
		assertThat(game.getSelectedUserId()).isEqualTo(lowest.getUserId());
		assertThat(game.getOutcome()).isEqualTo(TableGroupGameOutcome.ASSIGNED);
		assertThat(high.getStatus()).isEqualTo(TableGroupGamePlayerStatus.SAFE);
	}

	@Test
	void voteTieKeepsOnlyLeadersAndSwitchesToInteractiveDice() {
		Instant now = Instant.parse("2026-08-30T15:00:00Z");
		UUID gameId = UUID.randomUUID();
		TableGroupGame game = roundGame(gameId, TableGroupGamePhase.VOTE, 1, now);
		TableGroupGamePlayer a = player(gameId, UUID.randomUUID(), "a", now);
		TableGroupGamePlayer b = player(gameId, UUID.randomUUID(), "b", now);
		TableGroupGamePlayer c = player(gameId, UUID.randomUUID(), "c", now);
		TableGroupGamePlayer d = player(gameId, UUID.randomUUID(), "d", now);

		service.resolveVote(game, List.of(a, b, c, d), List.of(
				action(gameId, a.getUserId(), 1, TableGroupGamePhase.VOTE, TableGroupGameActionType.VOTE, b.getUserId(), null),
				action(gameId, b.getUserId(), 1, TableGroupGamePhase.VOTE, TableGroupGameActionType.VOTE, a.getUserId(), null),
				action(gameId, c.getUserId(), 1, TableGroupGamePhase.VOTE, TableGroupGameActionType.VOTE, a.getUserId(), null),
				action(gameId, d.getUserId(), 1, TableGroupGamePhase.VOTE, TableGroupGameActionType.VOTE, b.getUserId(), null)
		), now);

		assertThat(a.getStatus()).isEqualTo(TableGroupGamePlayerStatus.ACTIVE);
		assertThat(b.getStatus()).isEqualTo(TableGroupGamePlayerStatus.ACTIVE);
		assertThat(c.getStatus()).isEqualTo(TableGroupGamePlayerStatus.SAFE);
		assertThat(d.getStatus()).isEqualTo(TableGroupGamePlayerStatus.SAFE);
		assertThat(game.getPhase()).isEqualTo(TableGroupGamePhase.VOTE_TIE_DICE);
		assertThat(game.getRoundNumber()).isEqualTo(2);
	}

	@Test
	void voteAssignsTheOnlyHighestVotedPlayer() {
		Instant now = Instant.parse("2026-08-30T15:00:00Z");
		UUID gameId = UUID.randomUUID();
		TableGroupGame game = roundGame(gameId, TableGroupGamePhase.VOTE, 1, now);
		TableGroupGamePlayer selected = player(gameId, UUID.randomUUID(), "ece", now);
		TableGroupGamePlayer b = player(gameId, UUID.randomUUID(), "b", now);
		TableGroupGamePlayer c = player(gameId, UUID.randomUUID(), "c", now);

		service.resolveVote(game, List.of(selected, b, c), List.of(
				action(gameId, selected.getUserId(), 1, TableGroupGamePhase.VOTE, TableGroupGameActionType.VOTE, selected.getUserId(), null),
				action(gameId, b.getUserId(), 1, TableGroupGamePhase.VOTE, TableGroupGameActionType.VOTE, selected.getUserId(), null),
				action(gameId, c.getUserId(), 1, TableGroupGamePhase.VOTE, TableGroupGameActionType.VOTE, selected.getUserId(), null)
		), now);

		assertThat(game.getStatus()).isEqualTo(TableGroupGameStatus.COMPLETED);
		assertThat(game.getSelectedUserId()).isEqualTo(selected.getUserId());
		assertThat(game.getOutcome()).isEqualTo(TableGroupGameOutcome.ASSIGNED);
	}

	@Test
	void voteTimeoutRemovesCandidateAndIgnoresVotesTargetingThem() {
		Instant now = Instant.parse("2026-08-30T15:00:00Z");
		UUID gameId = UUID.randomUUID();
		TableGroupGame game = roundGame(gameId, TableGroupGamePhase.VOTE, 1, now);
		TableGroupGamePlayer a = player(gameId, UUID.randomUUID(), "a", now);
		TableGroupGamePlayer b = player(gameId, UUID.randomUUID(), "b", now);
		TableGroupGamePlayer timedOut = player(gameId, UUID.randomUUID(), "c", now);
		List<TableGroupGameAction> actions = List.of(
				action(gameId, a.getUserId(), 1, TableGroupGamePhase.VOTE, TableGroupGameActionType.VOTE, timedOut.getUserId(), null),
				action(gameId, b.getUserId(), 1, TableGroupGamePhase.VOTE, TableGroupGameActionType.VOTE, a.getUserId(), null)
		);
		when(playerRepository.findByGameIdAndStatusOrderByJoinedAtAscIdAsc(
				gameId, TableGroupGamePlayerStatus.ACTIVE)).thenReturn(List.of(a, b, timedOut));
		when(actionRepository.findByGameIdAndRoundNumberOrderByCreatedAtAscIdAsc(gameId, 1))
				.thenReturn(actions);

		service.resolveCurrentRound(game, now, true);

		assertThat(timedOut.getStatus()).isEqualTo(TableGroupGamePlayerStatus.TIMED_OUT);
		assertThat(game.getStatus()).isEqualTo(TableGroupGameStatus.COMPLETED);
		assertThat(game.getSelectedUserId()).isEqualTo(a.getUserId());
		assertThat(actions).allMatch(TableGroupGameAction::isRevealed);
	}

	@Test
	void timeoutCancelsWhenFewerThanTwoActivePlayersRemain() {
		Instant now = Instant.parse("2026-08-30T15:00:00Z");
		UUID gameId = UUID.randomUUID();
		TableGroupGame game = roundGame(gameId, TableGroupGamePhase.DICE, 1, now);
		TableGroupGamePlayer actor = player(gameId, UUID.randomUUID(), "a", now);
		TableGroupGamePlayer missing = player(gameId, UUID.randomUUID(), "b", now);
		when(playerRepository.findByGameIdAndStatusOrderByJoinedAtAscIdAsc(
				gameId, TableGroupGamePlayerStatus.ACTIVE)).thenReturn(List.of(actor, missing));
		when(actionRepository.findByGameIdAndRoundNumberOrderByCreatedAtAscIdAsc(gameId, 1))
				.thenReturn(List.of(action(
						gameId, actor.getUserId(), 1, TableGroupGamePhase.DICE,
						TableGroupGameActionType.ROLL, null, 2)));

		service.resolveCurrentRound(game, now, true);

		assertThat(missing.getStatus()).isEqualTo(TableGroupGamePlayerStatus.TIMED_OUT);
		assertThat(game.getStatus()).isEqualTo(TableGroupGameStatus.CANCELLED);
		assertThat(game.getCancellationReason()).isEqualTo("NOT_ENOUGH_PLAYERS");
	}

	@Test
	void twentiethUnresolvedRoundCancelsInsteadOfGrowingHistoryForever() {
		Instant now = Instant.parse("2026-08-30T15:00:00Z");
		UUID gameId = UUID.randomUUID();
		TableGroupGame game = roundGame(gameId, TableGroupGamePhase.RPS, 20, now);
		TableGroupGamePlayer a = player(gameId, UUID.randomUUID(), "a", now);
		TableGroupGamePlayer b = player(gameId, UUID.randomUUID(), "b", now);

		service.resolveRps(game, List.of(a, b), List.of(
				action(gameId, a.getUserId(), 20, TableGroupGamePhase.RPS, TableGroupGameActionType.ROCK, null, null),
				action(gameId, b.getUserId(), 20, TableGroupGamePhase.RPS, TableGroupGameActionType.ROCK, null, null)
		), now);

		assertThat(game.getStatus()).isEqualTo(TableGroupGameStatus.CANCELLED);
		assertThat(game.getCancellationReason()).isEqualTo("MAX_ROUNDS_REACHED");
	}

	@Test
	void onlyGameCreatorCanStartEvenWhenCallerOwnsTheTable() {
		UUID tableId = UUID.randomUUID();
		UUID tableOwner = UUID.randomUUID();
		UUID creator = UUID.randomUUID();
		UUID gameId = UUID.randomUUID();
		Instant now = Instant.parse("2026-08-30T15:00:00Z");
		TableGroup table = openTable(tableId, tableOwner, now);
		TableGroupGame game = TableGroupGame.builder()
				.id(gameId).revision(1).tableGroupId(tableId).createdBy(creator)
				.createdByUsername("creator").createRequestId(UUID.randomUUID())
				.topic(TableGroupGameTopic.WHO_PAYS).mode(TableGroupGameMode.DICE)
				.status(TableGroupGameStatus.LOBBY).phase(TableGroupGamePhase.LOBBY)
				.roundNumber(0).joinDeadlineAt(now.plusSeconds(60)).build();
		when(timeProvider.now()).thenReturn(now);
		when(tableGroupEntityFinder.getTableGroupByIdForUpdate(tableId)).thenReturn(table);
		when(gameRepository.findByIdForUpdate(gameId)).thenReturn(Optional.of(game));

		assertThatThrownBy(() -> service.start(tableOwner, tableId, gameId))
				.isInstanceOf(SoundConnectException.class);
		verify(gameRepository, never()).saveAndFlush(any());
	}

	@Test
	void createRejectsASecondActiveGameAfterConsumingRateLimit() {
		UUID tableId = UUID.randomUUID();
		UUID userId = UUID.randomUUID();
		UUID gameId = UUID.randomUUID();
		Instant now = Instant.parse("2026-08-30T15:00:00Z");
		TableGroupGame active = roundGame(gameId, TableGroupGamePhase.DICE, 1, now);
		active.setTableGroupId(tableId);
		when(timeProvider.now()).thenReturn(now);
		when(tableGroupEntityFinder.getTableGroupByIdForUpdate(tableId))
				.thenReturn(openTable(tableId, userId, now));
		when(gameRepository.findByTableGroupIdAndCreatedByAndCreateRequestId(any(), any(), any()))
				.thenReturn(Optional.empty());
		when(gameRepository.findFirstByTableGroupIdAndStatusInOrderByCreatedAtDesc(any(), any()))
				.thenReturn(Optional.of(active));
		when(gameRepository.findByIdForUpdate(gameId))
				.thenReturn(Optional.of(active));

		assertThatThrownBy(() -> service.create(
				userId, tableId,
				new TableGroupGameCreateRequestDto(UUID.randomUUID(), TableGroupGameMode.DICE)
		)).isInstanceOf(TableGroupGameActiveExistsException.class);
		verify(rateLimitGuard).checkGameCreateUser(userId);
	}

	@Test
	void activeGameConflictCommitsAnyDeadlineReconciliationBeforeReturningTheConflict() throws Exception {
		Transactional transaction = TableGroupGameServiceImpl.class
				.getMethod(
						"create",
						UUID.class,
						UUID.class,
						TableGroupGameCreateRequestDto.class
				)
				.getAnnotation(Transactional.class);

		assertThat(transaction).isNotNull();
		assertThat(transaction.noRollbackFor()).contains(TableGroupGameActiveExistsException.class);
	}

	@Test
	void createReconcilesAnExpiredActiveCandidateAndCreatesWhenItBecomesTerminal() {
		UUID tableId = UUID.randomUUID();
		UUID creatorId = UUID.randomUUID();
		UUID expiredGameId = UUID.randomUUID();
		UUID newGameId = UUID.randomUUID();
		UUID requestId = UUID.randomUUID();
		Instant now = Instant.parse("2026-08-30T15:00:00Z");
		TableGroup table = openTable(tableId, creatorId, now);
		TableGroupGame expired = TableGroupGame.builder()
				.id(expiredGameId).version(0).revision(2).tableGroupId(tableId)
				.createdBy(creatorId).createdByUsername("ece").createRequestId(UUID.randomUUID())
				.topic(TableGroupGameTopic.WHO_PAYS).mode(TableGroupGameMode.DICE)
				.status(TableGroupGameStatus.LOBBY).phase(TableGroupGamePhase.LOBBY)
				.roundNumber(0).joinDeadlineAt(now).build();
		TableGroupMessage expiredAnchor = TableGroupMessage.builder()
				.id(UUID.randomUUID()).tableGroupId(tableId).senderId(creatorId)
				.gameId(expiredGameId).content("game").messageType(MessageType.GAME).build();
		TableGroupGameResponseDto projection = mock(TableGroupGameResponseDto.class);
		TableGroupMessageResponseDto createdResponse = mock(TableGroupMessageResponseDto.class);
		User creator = User.builder().id(creatorId).username("ece").build();

		when(timeProvider.now()).thenReturn(now);
		when(tableGroupEntityFinder.getTableGroupByIdForUpdate(tableId)).thenReturn(table);
		when(gameRepository.findByTableGroupIdAndCreatedByAndCreateRequestId(
				tableId, creatorId, requestId)).thenReturn(Optional.empty());
		when(gameRepository.findFirstByTableGroupIdAndStatusInOrderByCreatedAtDesc(
				eq(tableId), anyCollection())).thenReturn(Optional.of(expired));
		when(gameRepository.findByIdForUpdate(expiredGameId)).thenReturn(Optional.of(expired));
		when(playerRepository.findByGameIdAndStatusOrderByJoinedAtAscIdAsc(
				expiredGameId, TableGroupGamePlayerStatus.ACTIVE))
				.thenReturn(List.of(player(expiredGameId, creatorId, "ece", now)));
		when(messageRepository.findByGameIdAndDeletedAtIsNull(expiredGameId))
				.thenReturn(Optional.of(expiredAnchor));
		when(messageRepository.countByTableGroupIdAndDeletedAtIsNull(tableId)).thenReturn(1L);
		when(userRepository.findById(creatorId)).thenReturn(Optional.of(creator));
		when(gameRepository.save(any())).thenAnswer(invocation -> {
			TableGroupGame saved = invocation.getArgument(0);
			saved.setId(newGameId);
			return saved;
		});
		when(playerRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
		when(messageRepository.save(any())).thenAnswer(invocation -> {
			TableGroupMessage saved = invocation.getArgument(0);
			if (saved.getId() == null) {
				saved.setId(UUID.randomUUID());
			}
			return saved;
		});
		when(projectionService.project(any(TableGroupGame.class))).thenReturn(projection);
		when(realtimePublisher.publishCreatedAfterCommit(eq(table), any(), eq(projection)))
				.thenReturn(createdResponse);

		assertThat(service.create(
				creatorId, tableId,
				new TableGroupGameCreateRequestDto(requestId, TableGroupGameMode.VOTE)
		)).isSameAs(createdResponse);
		assertThat(expired.getStatus()).isEqualTo(TableGroupGameStatus.CANCELLED);
		assertThat(expired.getCancellationReason())
				.isEqualTo("LOBBY_EXPIRED_NOT_ENOUGH_PLAYERS");
		verify(gameRepository).save(argThat(game -> game.getMode() == TableGroupGameMode.VOTE));
	}

	@Test
	void createRequestReplayReturnsTheOriginalAnchorWithoutCreatingAnything() {
		UUID tableId = UUID.randomUUID();
		UUID userId = UUID.randomUUID();
		UUID gameId = UUID.randomUUID();
		UUID requestId = UUID.randomUUID();
		Instant now = Instant.parse("2026-08-30T15:00:00Z");
		TableGroupGame existing = TableGroupGame.builder()
				.id(gameId).version(0).revision(7).tableGroupId(tableId)
				.createdBy(userId).createdByUsername("ece").createRequestId(requestId)
				.topic(TableGroupGameTopic.WHO_PAYS).mode(TableGroupGameMode.DICE)
				.status(TableGroupGameStatus.LOBBY).phase(TableGroupGamePhase.LOBBY)
				.roundNumber(0).joinDeadlineAt(now.plusSeconds(60)).build();
		TableGroupMessage anchor = TableGroupMessage.builder()
				.id(UUID.randomUUID()).tableGroupId(tableId).senderId(userId).gameId(gameId)
				.content("game").messageType(MessageType.GAME).build();
		TableGroupMessageResponseDto response = mock(TableGroupMessageResponseDto.class);
		TableGroupGameResponseDto gameResponse = mock(TableGroupGameResponseDto.class);
		when(tableGroupEntityFinder.getTableGroupByIdForUpdate(tableId))
				.thenReturn(openTable(tableId, userId, now));
		when(gameRepository.findByTableGroupIdAndCreatedByAndCreateRequestId(
				tableId, userId, requestId)).thenReturn(Optional.of(existing));
		when(gameRepository.findByIdForUpdate(gameId)).thenReturn(Optional.of(existing));
		when(timeProvider.now()).thenReturn(now);
		when(messageRepository.findByGameIdAndDeletedAtIsNull(gameId)).thenReturn(Optional.of(anchor));
		when(projectionService.project(existing)).thenReturn(gameResponse);
		when(messageMapper.toResponseDto(anchor, gameResponse)).thenReturn(response);

		assertThat(service.create(
				userId, tableId,
				new TableGroupGameCreateRequestDto(requestId, TableGroupGameMode.DICE)
		)).isSameAs(response);
		verify(gameRepository, never()).save(any());
		verify(playerRepository, never()).save(any());
		verify(messageRepository, never()).save(any());
		verify(rateLimitGuard).checkGameCreateUser(userId);
	}

	@Test
	void tableOwnerCancellationUsesOwnerReasonWhenSomeoneElseCreatedTheGame() {
		UUID tableId = UUID.randomUUID();
		UUID ownerId = UUID.randomUUID();
		UUID creatorId = UUID.randomUUID();
		UUID gameId = UUID.randomUUID();
		Instant now = Instant.parse("2026-08-30T15:00:00Z");
		TableGroup table = openTable(tableId, ownerId, now);
		TableGroupGame game = roundGame(gameId, TableGroupGamePhase.DICE, 1, now);
		game.setTableGroupId(tableId);
		game.setCreatedBy(creatorId);
		TableGroupMessage anchor = TableGroupMessage.builder()
				.id(UUID.randomUUID()).tableGroupId(tableId).senderId(creatorId).gameId(gameId)
				.content("game").messageType(MessageType.GAME).build();
		when(tableGroupEntityFinder.getTableGroupByIdForUpdate(tableId)).thenReturn(table);
		when(gameRepository.findByIdForUpdate(gameId)).thenReturn(Optional.of(game));
		when(timeProvider.now()).thenReturn(now);
		when(messageRepository.findByGameIdAndDeletedAtIsNull(gameId)).thenReturn(Optional.of(anchor));

		service.cancel(ownerId, tableId, gameId);

		assertThat(game.getStatus()).isEqualTo(TableGroupGameStatus.CANCELLED);
		assertThat(game.getCancellationReason()).isEqualTo("CANCELLED_BY_OWNER");
	}

	@Test
	void cancelReconcilesExpiredDeadlineBeforeApplyingManualCancellation() {
		UUID tableId = UUID.randomUUID();
		UUID creatorId = UUID.randomUUID();
		UUID gameId = UUID.randomUUID();
		Instant now = Instant.parse("2026-08-30T15:00:00Z");
		TableGroupGame game = TableGroupGame.builder()
				.id(gameId).version(0).revision(2).tableGroupId(tableId)
				.createdBy(creatorId).createdByUsername("ece").createRequestId(UUID.randomUUID())
				.topic(TableGroupGameTopic.WHO_PAYS).mode(TableGroupGameMode.DICE)
				.status(TableGroupGameStatus.LOBBY).phase(TableGroupGamePhase.LOBBY)
				.roundNumber(0).joinDeadlineAt(now).build();
		TableGroupMessage anchor = TableGroupMessage.builder()
				.id(UUID.randomUUID()).tableGroupId(tableId).senderId(creatorId).gameId(gameId)
				.content("game").messageType(MessageType.GAME).build();
		TableGroupMessageResponseDto reconciledResponse = mock(TableGroupMessageResponseDto.class);
		when(timeProvider.now()).thenReturn(now);
		when(tableGroupEntityFinder.getTableGroupByIdForUpdate(tableId))
				.thenReturn(openTable(tableId, creatorId, now));
		when(gameRepository.findByIdForUpdate(gameId)).thenReturn(Optional.of(game));
		when(playerRepository.findByGameIdAndStatusOrderByJoinedAtAscIdAsc(
				gameId, TableGroupGamePlayerStatus.ACTIVE))
				.thenReturn(List.of(player(gameId, creatorId, "ece", now)));
		when(messageRepository.findByGameIdAndDeletedAtIsNull(gameId)).thenReturn(Optional.of(anchor));
		when(realtimePublisher.publishUpdatedAfterCommit(eq(tableId), eq(anchor), any()))
				.thenReturn(reconciledResponse);

		assertThat(service.cancel(creatorId, tableId, gameId)).isSameAs(reconciledResponse);
		assertThat(game.getCancellationReason())
				.isEqualTo("LOBBY_EXPIRED_NOT_ENOUGH_PLAYERS");
		verify(rateLimitGuard).checkGameCommandUser(creatorId);
	}

	@Test
	void actionRequestReplayReturnsSameAnchorWithoutRollingOrPersistingAgain() {
		UUID tableId = UUID.randomUUID();
		UUID userId = UUID.randomUUID();
		UUID gameId = UUID.randomUUID();
		UUID requestId = UUID.randomUUID();
		Instant now = Instant.parse("2026-08-30T15:00:00Z");
		TableGroupGame game = roundGame(gameId, TableGroupGamePhase.RPS, 1, now);
		game.setTableGroupId(tableId);
		game.setCreatedBy(userId);
		TableGroupGameAction existing = action(
				gameId, userId, 1, TableGroupGamePhase.RPS,
				TableGroupGameActionType.ROCK, null, null);
		existing.setRequestId(requestId);
		TableGroupMessage anchor = TableGroupMessage.builder()
				.id(UUID.randomUUID()).tableGroupId(tableId).senderId(userId).gameId(gameId)
				.content("game").messageType(MessageType.GAME).build();
		TableGroupMessageResponseDto response = mock(TableGroupMessageResponseDto.class);
		TableGroupGameResponseDto gameResponse = mock(TableGroupGameResponseDto.class);
		when(timeProvider.now()).thenReturn(now);
		when(tableGroupEntityFinder.getTableGroupByIdForUpdate(tableId))
				.thenReturn(openTable(tableId, userId, now));
		when(gameRepository.findByIdForUpdate(gameId)).thenReturn(Optional.of(game));
		when(playerRepository.findByGameIdAndUserId(gameId, userId))
				.thenReturn(Optional.of(player(gameId, userId, "ece", now)));
		when(actionRepository.findByGameIdAndActorUserIdAndRequestId(gameId, userId, requestId))
				.thenReturn(Optional.of(existing));
		when(messageRepository.findByGameIdAndDeletedAtIsNull(gameId)).thenReturn(Optional.of(anchor));
		when(projectionService.project(game)).thenReturn(gameResponse);
		when(messageMapper.toResponseDto(anchor, gameResponse)).thenReturn(response);

		assertThat(service.submitAction(
				userId, tableId, gameId,
				new TableGroupGameActionRequestDto(requestId, TableGroupGameActionType.ROCK, null)
		)).isSameAs(response);
		verify(actionRepository, never()).save(any());
		verify(diceRoller, never()).roll();
		verify(rateLimitGuard).checkGameCommandUser(userId);
	}

	private void assertValidationError(ThrowingCallable invocation) {
		assertThatThrownBy(invocation::call)
				.isInstanceOf(SoundConnectException.class)
				.hasFieldOrPropertyWithValue("errorType", ErrorType.VALIDATION_ERROR);
	}

	private void invokeMutation(
			GameMutation mutation,
			UUID userId,
			UUID tableId,
			UUID gameId
	) {
		switch (mutation) {
			case CREATE -> service.create(
					userId,
					tableId,
					new TableGroupGameCreateRequestDto(UUID.randomUUID(), TableGroupGameMode.DICE)
			);
			case JOIN -> service.join(userId, tableId, gameId);
			case LEAVE -> service.leave(userId, tableId, gameId);
			case START -> service.start(userId, tableId, gameId);
			case CANCEL -> service.cancel(userId, tableId, gameId);
			case SUBMIT_ACTION -> service.submitAction(
					userId,
					tableId,
					gameId,
					new TableGroupGameActionRequestDto(
							UUID.randomUUID(),
							TableGroupGameActionType.ROCK,
							null
					)
			);
		}
	}

	private enum GameMutation {
		CREATE,
		JOIN,
		LEAVE,
		START,
		CANCEL,
		SUBMIT_ACTION
	}

	private TableGroup openTable(UUID tableId, UUID acceptedUserId, Instant now) {
		TableGroup tableGroup = TableGroup.builder()
				.ownerId(acceptedUserId)
				.status(TableGroupStatus.ACTIVE)
				.expiresAt(now.plusSeconds(3600))
				.participants(new HashSet<>(Set.of(TableGroupParticipant.builder()
						.userId(acceptedUserId)
						.status(ParticipantStatus.ACCEPTED)
						.joinedAt(now)
						.build())))
				.build();
		tableGroup.setId(tableId);
		return tableGroup;
	}

	private TableGroupGame inProgressVote(UUID gameId, UUID tableId, UUID creator, Instant now) {
		return TableGroupGame.builder()
				.id(gameId).version(0).revision(3).tableGroupId(tableId)
				.createdBy(creator).createdByUsername("ece").createRequestId(UUID.randomUUID())
				.topic(TableGroupGameTopic.WHO_PAYS).mode(TableGroupGameMode.VOTE)
				.status(TableGroupGameStatus.IN_PROGRESS).phase(TableGroupGamePhase.VOTE)
				.roundNumber(1).actionDeadlineAt(now.plusSeconds(20)).build();
	}

	private TableGroupGamePlayer player(UUID gameId, UUID userId, String username, Instant now) {
		return TableGroupGamePlayer.builder()
				.id(UUID.randomUUID()).gameId(gameId).userId(userId).username(username)
				.status(TableGroupGamePlayerStatus.ACTIVE).joinedAt(now).build();
	}

	private TableGroupGame roundGame(
			UUID gameId,
			TableGroupGamePhase phase,
			int round,
			Instant now
	) {
		TableGroupGameMode mode = switch (phase) {
			case RPS -> TableGroupGameMode.ROCK_PAPER_SCISSORS;
			case VOTE, VOTE_TIE_DICE -> TableGroupGameMode.VOTE;
			default -> TableGroupGameMode.DICE;
		};
		return TableGroupGame.builder()
				.id(gameId).version(0).revision(1).tableGroupId(UUID.randomUUID())
				.createdBy(UUID.randomUUID()).createdByUsername("creator").createRequestId(UUID.randomUUID())
				.topic(TableGroupGameTopic.WHO_PAYS).mode(mode)
				.status(TableGroupGameStatus.IN_PROGRESS).phase(phase)
				.roundNumber(round).actionDeadlineAt(now.plusSeconds(20)).build();
	}

	private TableGroupGameAction action(
			UUID gameId,
			UUID actor,
			int round,
			TableGroupGamePhase phase,
			TableGroupGameActionType type,
			UUID target,
			Integer value
	) {
		return TableGroupGameAction.builder()
				.id(UUID.randomUUID()).gameId(gameId).requestId(UUID.randomUUID())
				.roundNumber(round).phase(phase).actorUserId(actor).action(type)
				.targetUserId(target).value(value).revealed(false).build();
	}
}
