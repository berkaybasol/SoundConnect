package com.berkayb.soundconnect.modules.tablegroup.chat.service;

import com.berkayb.soundconnect.modules.tablegroup.abuse.TableGroupRateLimitGuard;
import com.berkayb.soundconnect.modules.tablegroup.chat.cache.TableGroupChatUnreadHelper;
import com.berkayb.soundconnect.modules.tablegroup.chat.dto.request.TableGroupMessageRequestDto;
import com.berkayb.soundconnect.modules.tablegroup.chat.dto.response.TableGroupMessageResponseDto;
import com.berkayb.soundconnect.modules.tablegroup.chat.entity.TableGroupMessage;
import com.berkayb.soundconnect.modules.tablegroup.chat.enums.MessageType;
import com.berkayb.soundconnect.modules.tablegroup.chat.mapper.TableGroupMessageMapper;
import com.berkayb.soundconnect.modules.tablegroup.chat.repository.TableGroupMessageRepository;
import com.berkayb.soundconnect.modules.tablegroup.entity.TableGroup;
import com.berkayb.soundconnect.modules.tablegroup.game.service.TableGroupGameProjectionService;
import com.berkayb.soundconnect.modules.tablegroup.entity.TableGroupParticipant;
import com.berkayb.soundconnect.modules.tablegroup.enums.ParticipantStatus;
import com.berkayb.soundconnect.modules.tablegroup.enums.TableGroupStatus;
import com.berkayb.soundconnect.modules.tablegroup.observability.TableGroupMetrics;
import com.berkayb.soundconnect.modules.tablegroup.support.TableGroupEntityFinder;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.realtime.WebSocketChannels;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.*;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * TableGroup chat business kurallari icin unit test.
 */
@ExtendWith(MockitoExtension.class)
class TableGroupChatServiceImplTest {
	
	@Mock
	private TableGroupMessageRepository messageRepository;
	
	@Mock
	private TableGroupMessageMapper messageMapper;
	
	@Mock
	private SimpMessagingTemplate messagingTemplate;
	
	@Mock
	private TableGroupEntityFinder tableGroupEntityFinder;

	@Mock
	private TableGroupChatUnreadHelper unreadHelper;

	@Mock
	private TableGroupRateLimitGuard rateLimitGuard;

	@Mock
	private TableGroupMetrics metrics;

	@Mock
	private TableGroupGameProjectionService gameProjectionService;
	
	@InjectMocks
	private TableGroupChatServiceImpl chatService;
	
	private TableGroup createActiveGroupWithAcceptedParticipants(UUID tableGroupId, UUID senderId, UUID otherUserId) {
		TableGroup group = TableGroup.builder()
		                             .ownerId(UUID.randomUUID())
		                             .maxPersonCount(4)
		                             .genderPrefs(List.of("MALE", "FEMALE"))
		                             .ageMin(20)
		                             .ageMax(30)
		                             .expiresAt(Instant.now().plusSeconds(7_200))
		                             .status(TableGroupStatus.ACTIVE)
		                             .participants(new HashSet<>())
		                             .build();
		
		group.getParticipants().add(
				TableGroupParticipant.builder()
				                     .userId(senderId)
				                     .status(ParticipantStatus.ACCEPTED)
				                     .joinedAt(Instant.now())
				                     .build()
		);
		group.getParticipants().add(
				TableGroupParticipant.builder()
				                     .userId(otherUserId)
				                     .status(ParticipantStatus.ACCEPTED)
				                     .joinedAt(Instant.now())
				                     .build()
		);
		
		return group;
	}
	
	// -------------------- sendMessage --------------------
	
	@Test
	void sendMessage_whenSenderIsAcceptedAndTableActive_shouldSaveMessageAndIncrementUnreadForOthers() {
		// given
		UUID tableGroupId = UUID.randomUUID();
		UUID senderId = UUID.randomUUID();
		UUID otherUserId = UUID.randomUUID();
		
		TableGroup group = createActiveGroupWithAcceptedParticipants(tableGroupId, senderId, otherUserId);
		
		when(tableGroupEntityFinder.getTableGroupByIdForUpdate(tableGroupId))
				.thenReturn(group);
		
		TableGroupMessageRequestDto request = new TableGroupMessageRequestDto(
				"kanka nerdesiniz",
				MessageType.TEXT,
				UUID.randomUUID()
		);
		
		TableGroupMessage saved = TableGroupMessage.builder()
		                                           .tableGroupId(tableGroupId)
		                                           .senderId(senderId)
		                                           .content(request.content())
		                                           .messageType(MessageType.TEXT)
		                                           .deletedAt(null)
		                                           .build();
		
		when(messageRepository.save(any(TableGroupMessage.class)))
				.thenReturn(saved);
		
		TableGroupMessageResponseDto dto = new TableGroupMessageResponseDto(
				UUID.randomUUID(),
				tableGroupId,
				senderId,
				request.content(),
				MessageType.TEXT,
				Instant.now(),
				null
		);
		when(messageMapper.toResponseDto(saved)).thenReturn(dto);
		
		// when
		TableGroupMessageResponseDto result = chatService.sendMessage(senderId, tableGroupId, request);
		
		// then
		assertThat(result).isEqualTo(dto);
		verify(rateLimitGuard).checkMessageUser(senderId, tableGroupId);
		verify(metrics).messageSent();
		
		// unread: sadece diger accepted user icin increment
		verify(unreadHelper, times(1)).incrementUnread(otherUserId, tableGroupId);
		verify(unreadHelper, never()).incrementUnread(senderId, tableGroupId);
		
		// WS publish denemesi
		verify(messagingTemplate).convertAndSend(
				eq(WebSocketChannels.tableGroup(tableGroupId)),
				eq(dto)
		);
	}

	@Test
	void sendMessage_whenExactClientKeyIsRetried_shouldReplayWithoutDuplicateSideEffects() {
		UUID tableGroupId = UUID.randomUUID();
		UUID senderId = UUID.randomUUID();
		UUID clientMessageId = UUID.randomUUID();
		TableGroup group = createActiveGroupWithAcceptedParticipants(
				tableGroupId, senderId, UUID.randomUUID());
		TableGroupMessage persisted = TableGroupMessage.builder()
				.id(UUID.randomUUID())
				.tableGroupId(tableGroupId)
				.senderId(senderId)
				.clientMessageId(clientMessageId)
				.content("same message")
				.messageType(MessageType.TEXT)
				.build();
		TableGroupMessageResponseDto dto = new TableGroupMessageResponseDto(
				persisted.getId(), tableGroupId, senderId, persisted.getContent(),
				MessageType.TEXT, Instant.now(), null, clientMessageId, null);

		when(messageRepository.findByTableGroupIdAndSenderIdAndClientMessageId(
				tableGroupId, senderId, clientMessageId)).thenReturn(Optional.of(persisted));
		when(messageMapper.toResponseDto(persisted)).thenReturn(dto);

		assertThat(chatService.sendMessage(
				senderId,
				tableGroupId,
				new TableGroupMessageRequestDto("  same message  ", MessageType.TEXT, clientMessageId)
		)).isEqualTo(dto);

		verifyNoInteractions(rateLimitGuard, tableGroupEntityFinder);
		verify(messageRepository, never()).acquireClientMessageKeyLock(any(), any(), any());
		verify(messageRepository, never()).countByTableGroupIdAndDeletedAtIsNull(any());
		verify(messageRepository, never()).save(any());
		verifyNoInteractions(unreadHelper, messagingTemplate, metrics);
	}

	@Test
	void sendMessage_whenClientKeyPayloadDiffers_shouldReturnStableConflict() {
		UUID tableGroupId = UUID.randomUUID();
		UUID senderId = UUID.randomUUID();
		UUID clientMessageId = UUID.randomUUID();
		TableGroupMessage persisted = TableGroupMessage.builder()
				.tableGroupId(tableGroupId)
				.senderId(senderId)
				.clientMessageId(clientMessageId)
				.content("original")
				.messageType(MessageType.TEXT)
				.build();

		when(messageRepository.findByTableGroupIdAndSenderIdAndClientMessageId(
				tableGroupId, senderId, clientMessageId)).thenReturn(Optional.of(persisted));

		assertThatThrownBy(() -> chatService.sendMessage(
				senderId,
				tableGroupId,
				new TableGroupMessageRequestDto("changed", null, clientMessageId)
		))
				.isInstanceOf(SoundConnectException.class)
				.hasFieldOrPropertyWithValue(
						"errorType", ErrorType.TABLE_GROUP_MESSAGE_IDEMPOTENCY_CONFLICT);

		verifyNoInteractions(rateLimitGuard, tableGroupEntityFinder);
		verify(messageRepository, never()).acquireClientMessageKeyLock(any(), any(), any());
		verify(messageRepository, never()).save(any());
		verifyNoInteractions(messageMapper, unreadHelper, messagingTemplate, metrics);
	}

	@Test
	void sendMessage_whenOriginalCommitsBeforeRetryGetsLock_shouldReplayAfterLock() {
		UUID tableGroupId = UUID.randomUUID();
		UUID senderId = UUID.randomUUID();
		UUID clientMessageId = UUID.randomUUID();
		TableGroupMessage persisted = TableGroupMessage.builder()
				.id(UUID.randomUUID())
				.tableGroupId(tableGroupId)
				.senderId(senderId)
				.clientMessageId(clientMessageId)
				.content("racing retry")
				.messageType(MessageType.TEXT)
				.build();
		TableGroupMessageResponseDto dto = new TableGroupMessageResponseDto(
				persisted.getId(), tableGroupId, senderId, persisted.getContent(),
				MessageType.TEXT, Instant.now(), null, clientMessageId, null);

		when(messageRepository.findByTableGroupIdAndSenderIdAndClientMessageId(
				tableGroupId, senderId, clientMessageId))
				.thenReturn(Optional.empty(), Optional.of(persisted));
		when(messageMapper.toResponseDto(persisted)).thenReturn(dto);

		assertThat(chatService.sendMessage(
				senderId,
				tableGroupId,
				new TableGroupMessageRequestDto("racing retry", MessageType.TEXT, clientMessageId)
		)).isEqualTo(dto);

		verifyNoInteractions(rateLimitGuard);
		verifyNoInteractions(tableGroupEntityFinder);
		verify(messageRepository).acquireClientMessageKeyLock(
				tableGroupId, senderId, clientMessageId);
		verify(messageRepository, times(2)).findByTableGroupIdAndSenderIdAndClientMessageId(
				tableGroupId, senderId, clientMessageId);
		verify(messageRepository, never()).countByTableGroupIdAndDeletedAtIsNull(any());
		verify(messageRepository, never()).save(any());
		verifyNoInteractions(unreadHelper, messagingTemplate, metrics);
	}

	@Test
	void sendMessage_whenTransactionSynchronizationIsActive_shouldDeliverOnlyAfterCommit() {
		UUID tableGroupId = UUID.randomUUID();
		UUID senderId = UUID.randomUUID();
		UUID otherUserId = UUID.randomUUID();
		TableGroup group = createActiveGroupWithAcceptedParticipants(tableGroupId, senderId, otherUserId);
		TableGroupMessageRequestDto request = new TableGroupMessageRequestDto(
				"commit first", MessageType.TEXT, UUID.randomUUID());
		TableGroupMessage saved = TableGroupMessage.builder()
				.tableGroupId(tableGroupId)
				.senderId(senderId)
				.content(request.content())
				.messageType(MessageType.TEXT)
				.build();
		TableGroupMessageResponseDto dto = new TableGroupMessageResponseDto(
				UUID.randomUUID(),
				tableGroupId,
				senderId,
				request.content(),
				MessageType.TEXT,
				Instant.now(),
				null
		);
		when(tableGroupEntityFinder.getTableGroupByIdForUpdate(tableGroupId)).thenReturn(group);
		when(messageRepository.save(any(TableGroupMessage.class))).thenReturn(saved);
		when(messageMapper.toResponseDto(saved)).thenReturn(dto);

		TransactionSynchronizationManager.initSynchronization();
		try {
			assertThat(chatService.sendMessage(senderId, tableGroupId, request)).isEqualTo(dto);
			verifyNoInteractions(unreadHelper, messagingTemplate, metrics);

			List<TransactionSynchronization> synchronizations =
					TransactionSynchronizationManager.getSynchronizations();
			assertThat(synchronizations).hasSize(1);
			synchronizations.getFirst().afterCommit();

			verify(unreadHelper).incrementUnread(otherUserId, tableGroupId);
			verify(messagingTemplate).convertAndSend(WebSocketChannels.tableGroup(tableGroupId), dto);
			verify(metrics).messageSent();
		} finally {
			TransactionSynchronizationManager.clearSynchronization();
		}
	}

	@Test
	void sendMessage_whenUnreadDeliveryFails_shouldStillBroadcastCommittedMessage() {
		UUID tableGroupId = UUID.randomUUID();
		UUID senderId = UUID.randomUUID();
		UUID otherUserId = UUID.randomUUID();
		TableGroup group = createActiveGroupWithAcceptedParticipants(tableGroupId, senderId, otherUserId);
		TableGroupMessageRequestDto request = new TableGroupMessageRequestDto(
				"selam", MessageType.TEXT, UUID.randomUUID());
		TableGroupMessage saved = TableGroupMessage.builder()
				.tableGroupId(tableGroupId)
				.senderId(senderId)
				.content(request.content())
				.messageType(MessageType.TEXT)
				.build();
		TableGroupMessageResponseDto dto = new TableGroupMessageResponseDto(
				UUID.randomUUID(), tableGroupId, senderId, request.content(), MessageType.TEXT,
				Instant.now(), null
		);
		when(tableGroupEntityFinder.getTableGroupByIdForUpdate(tableGroupId)).thenReturn(group);
		when(messageRepository.save(any(TableGroupMessage.class))).thenReturn(saved);
		when(messageMapper.toResponseDto(saved)).thenReturn(dto);
		doThrow(new IllegalStateException("redis unavailable"))
				.when(unreadHelper).incrementUnread(otherUserId, tableGroupId);

		assertThat(chatService.sendMessage(senderId, tableGroupId, request)).isEqualTo(dto);

		verify(messagingTemplate).convertAndSend(WebSocketChannels.tableGroup(tableGroupId), dto);
		verify(metrics).unreadCacheFailed("delivery");
	}

	@Test
	void sendMessage_whenRealtimePublishFails_shouldReturnPersistedMessageAndRecordMetric() {
		UUID tableGroupId = UUID.randomUUID();
		UUID senderId = UUID.randomUUID();
		UUID otherUserId = UUID.randomUUID();
		TableGroup group = createActiveGroupWithAcceptedParticipants(tableGroupId, senderId, otherUserId);
		TableGroupMessageRequestDto request = new TableGroupMessageRequestDto(
				"persist me", MessageType.TEXT, UUID.randomUUID());
		TableGroupMessage saved = TableGroupMessage.builder()
				.tableGroupId(tableGroupId)
				.senderId(senderId)
				.content(request.content())
				.messageType(MessageType.TEXT)
				.build();
		TableGroupMessageResponseDto dto = new TableGroupMessageResponseDto(
				UUID.randomUUID(), tableGroupId, senderId, request.content(), MessageType.TEXT,
				Instant.now(), null
		);
		when(tableGroupEntityFinder.getTableGroupByIdForUpdate(tableGroupId)).thenReturn(group);
		when(messageRepository.save(any(TableGroupMessage.class))).thenReturn(saved);
		when(messageMapper.toResponseDto(saved)).thenReturn(dto);
		doThrow(new IllegalStateException("broker unavailable"))
				.when(messagingTemplate).convertAndSend(WebSocketChannels.tableGroup(tableGroupId), dto);

		assertThat(chatService.sendMessage(senderId, tableGroupId, request)).isEqualTo(dto);

		verify(metrics).messageSent();
		verify(metrics).realtimePublishFailed();
	}

	@Test
	void sendMessage_whenRateLimitRejects_shouldNotPersistOrDeliver() {
		UUID tableGroupId = UUID.randomUUID();
		UUID senderId = UUID.randomUUID();
		TableGroupMessageRequestDto request = new TableGroupMessageRequestDto(
				"spam", MessageType.TEXT, UUID.randomUUID());
		doThrow(new SoundConnectException(ErrorType.BAD_REQUEST, "limited"))
				.when(rateLimitGuard).checkMessageUser(senderId, tableGroupId);

		assertThatThrownBy(() -> chatService.sendMessage(senderId, tableGroupId, request))
				.isInstanceOf(SoundConnectException.class);
		verifyNoInteractions(tableGroupEntityFinder);
		verify(messageRepository, times(2)).findByTableGroupIdAndSenderIdAndClientMessageId(
				eq(tableGroupId), eq(senderId), any(UUID.class));
		verify(messageRepository, never()).save(any());
		verifyNoInteractions(unreadHelper, messagingTemplate);
	}

	@Test
	void sendMessage_serializesKeyThenChecksUserBeforeAggregateAndTableAfterAccess() {
		UUID tableGroupId = UUID.randomUUID();
		UUID senderId = UUID.randomUUID();
		UUID clientMessageId = UUID.randomUUID();
		TableGroup group = createActiveGroupWithAcceptedParticipants(
				tableGroupId, senderId, UUID.randomUUID());
		TableGroupMessage saved = TableGroupMessage.builder()
				.tableGroupId(tableGroupId)
				.senderId(senderId)
				.content("ordered")
				.messageType(MessageType.TEXT)
				.build();
		when(tableGroupEntityFinder.getTableGroupByIdForUpdate(tableGroupId)).thenReturn(group);
		when(messageRepository.save(any(TableGroupMessage.class))).thenReturn(saved);

		chatService.sendMessage(
				senderId,
				tableGroupId,
				new TableGroupMessageRequestDto("ordered", MessageType.TEXT, clientMessageId)
		);

		InOrder order = inOrder(
				messageRepository,
				rateLimitGuard,
				tableGroupEntityFinder
		);
		order.verify(messageRepository).findByTableGroupIdAndSenderIdAndClientMessageId(
				tableGroupId, senderId, clientMessageId);
		order.verify(messageRepository).acquireClientMessageKeyLock(
				tableGroupId, senderId, clientMessageId);
		order.verify(messageRepository).findByTableGroupIdAndSenderIdAndClientMessageId(
				tableGroupId, senderId, clientMessageId);
		order.verify(rateLimitGuard).checkMessageUser(senderId, tableGroupId);
		order.verify(tableGroupEntityFinder).getTableGroupByIdForUpdate(tableGroupId);
		order.verify(rateLimitGuard).checkMessageTable(tableGroupId);
	}

	@Test
	void sendMessage_whenSharedBucketFails_shouldNotPersistAfterAggregateLock() {
		UUID tableGroupId = UUID.randomUUID();
		UUID senderId = UUID.randomUUID();
		TableGroup group = createActiveGroupWithAcceptedParticipants(
				tableGroupId, senderId, UUID.randomUUID());
		when(tableGroupEntityFinder.getTableGroupByIdForUpdate(tableGroupId)).thenReturn(group);
		doThrow(new SoundConnectException(ErrorType.TABLE_GROUP_RATE_LIMIT_UNAVAILABLE))
				.when(rateLimitGuard).checkMessageTable(tableGroupId);

		assertThatThrownBy(() -> chatService.sendMessage(
				senderId,
				tableGroupId,
				new TableGroupMessageRequestDto("no lock", MessageType.TEXT, UUID.randomUUID())
		)).isInstanceOf(SoundConnectException.class)
				.hasFieldOrPropertyWithValue(
						"errorType", ErrorType.TABLE_GROUP_RATE_LIMIT_UNAVAILABLE);

		verify(tableGroupEntityFinder).getTableGroupByIdForUpdate(tableGroupId);
		verify(messageRepository, times(2)).findByTableGroupIdAndSenderIdAndClientMessageId(
				eq(tableGroupId), eq(senderId), any(UUID.class));
		verify(messageRepository, never()).save(any());
	}

	@Test
	void sendMessage_whenAggregateStorageCapIsReached_shouldRejectUnderTheGroupLock() {
		UUID tableGroupId = UUID.randomUUID();
		UUID senderId = UUID.randomUUID();
		TableGroup group = createActiveGroupWithAcceptedParticipants(
				tableGroupId, senderId, UUID.randomUUID());
		when(tableGroupEntityFinder.getTableGroupByIdForUpdate(tableGroupId)).thenReturn(group);
		when(messageRepository.countByTableGroupIdAndDeletedAtIsNull(tableGroupId)).thenReturn(10_000L);

		assertThatThrownBy(() -> chatService.sendMessage(
				senderId,
				tableGroupId,
				new TableGroupMessageRequestDto("one too many", MessageType.TEXT, UUID.randomUUID())
		))
				.isInstanceOf(SoundConnectException.class)
				.hasFieldOrPropertyWithValue("errorType", ErrorType.TABLE_GROUP_CHAT_LIMIT_REACHED);
		verify(rateLimitGuard).checkMessageUser(senderId, tableGroupId);
		verify(messageRepository, never()).save(any());
		verifyNoInteractions(unreadHelper, messagingTemplate, metrics);
	}

	@Test
	void sendMessage_whenUserSpoofsSystemType_shouldRejectBeforeRateLimitOrSave() {
		UUID tableGroupId = UUID.randomUUID();
		UUID senderId = UUID.randomUUID();
		assertThatThrownBy(() -> chatService.sendMessage(
				senderId,
				tableGroupId,
				new TableGroupMessageRequestDto("fake system event", MessageType.SYSTEM, UUID.randomUUID())
		))
				.isInstanceOf(SoundConnectException.class)
				.hasFieldOrPropertyWithValue("errorType", ErrorType.VALIDATION_ERROR);
		verifyNoInteractions(tableGroupEntityFinder);
		verifyNoInteractions(rateLimitGuard, messageRepository, unreadHelper, messagingTemplate, metrics);
	}

	@Test
	void sendMessage_whenClientMessageIdIsMissing_shouldRejectBeforeRateLimitOrSave() {
		UUID tableGroupId = UUID.randomUUID();
		UUID senderId = UUID.randomUUID();

		assertThatThrownBy(() -> chatService.sendMessage(
				senderId,
				tableGroupId,
				new TableGroupMessageRequestDto("missing key", MessageType.TEXT, null)
		))
				.isInstanceOf(SoundConnectException.class)
				.hasFieldOrPropertyWithValue("errorType", ErrorType.VALIDATION_ERROR);

		verifyNoInteractions(rateLimitGuard, tableGroupEntityFinder, messageRepository,
				unreadHelper, messagingTemplate, metrics);
	}
	
	@Test
	void sendMessage_whenTableNotActive_shouldThrowTABLE_GROUP_NOT_FOUND() {
		// given
		UUID tableGroupId = UUID.randomUUID();
		UUID senderId = UUID.randomUUID();
		
		TableGroup group = TableGroup.builder()
		                             .ownerId(UUID.randomUUID())
		                             .maxPersonCount(3)
		                             .genderPrefs(List.of("MALE", "FEMALE", "OTHER"))
		                             .ageMin(20)
		                             .ageMax(30)
		                             .expiresAt(Instant.now().plusSeconds(3_600))
		                             .status(TableGroupStatus.CANCELLED)
		                             .participants(new HashSet<>())
		                             .build();
		
		when(tableGroupEntityFinder.getTableGroupByIdForUpdate(tableGroupId))
				.thenReturn(group);
		
		TableGroupMessageRequestDto request = new TableGroupMessageRequestDto(
				"selam",
				MessageType.TEXT,
				UUID.randomUUID()
		);
		
		// when / then
		assertThatThrownBy(() -> chatService.sendMessage(senderId, tableGroupId, request))
				.isInstanceOf(SoundConnectException.class)
				.hasFieldOrPropertyWithValue("errorType", ErrorType.TABLE_GROUP_NOT_FOUND);
		verify(rateLimitGuard).checkMessageUser(senderId, tableGroupId);
		verify(rateLimitGuard, never()).checkMessageTable(any());
	}
	
	@Test
	void sendMessage_whenSenderNotAccepted_shouldThrowUnauthorized() {
		// given
		UUID tableGroupId = UUID.randomUUID();
		UUID senderId = UUID.randomUUID();
		
		TableGroup group = TableGroup.builder()
		                             .ownerId(UUID.randomUUID())
		                             .maxPersonCount(3)
		                             .genderPrefs(List.of("MALE", "FEMALE", "OTHER"))
		                             .ageMin(20)
		                             .ageMax(30)
		                             .expiresAt(Instant.now().plusSeconds(3_600))
		                             .status(TableGroupStatus.ACTIVE)
		                             .participants(new HashSet<>())
		                             .build();
		
		// Participant var ama PENDING / REJECTED vs olabilir, fark etmez, accepted degil
		group.getParticipants().add(
				TableGroupParticipant.builder()
				                     .userId(senderId)
				                     .status(ParticipantStatus.PENDING)
				                     .joinedAt(Instant.now())
				                     .build()
		);
		
		when(tableGroupEntityFinder.getTableGroupByIdForUpdate(tableGroupId))
				.thenReturn(group);
		
		TableGroupMessageRequestDto request = new TableGroupMessageRequestDto(
				"selam",
				MessageType.TEXT,
				UUID.randomUUID()
		);
		
		// when / then
		assertThatThrownBy(() -> chatService.sendMessage(senderId, tableGroupId, request))
				.isInstanceOf(SoundConnectException.class)
				.hasFieldOrPropertyWithValue("errorType", ErrorType.FORBIDDEN_ACCESS);
		
		verify(rateLimitGuard).checkMessageUser(senderId, tableGroupId);
		verify(rateLimitGuard, never()).checkMessageTable(any());
		verify(messageRepository, never()).save(any());
		verify(unreadHelper, never()).incrementUnread(any(), any());
	}

	@Test
	void sendMessage_whenSenderIsNotAcceptedUnderLock_shouldNotChargeSharedBucket() {
		UUID tableGroupId = UUID.randomUUID();
		UUID senderId = UUID.randomUUID();
		TableGroup lockedGroup = TableGroup.builder()
				.ownerId(UUID.randomUUID())
				.expiresAt(Instant.now().plusSeconds(3_600))
				.status(TableGroupStatus.ACTIVE)
				.participants(new HashSet<>())
				.build();
		lockedGroup.getParticipants().add(TableGroupParticipant.builder()
				.userId(senderId)
				.status(ParticipantStatus.PENDING)
				.joinedAt(Instant.now())
				.build());
		when(tableGroupEntityFinder.getTableGroupByIdForUpdate(tableGroupId)).thenReturn(lockedGroup);

		assertThatThrownBy(() -> chatService.sendMessage(
				senderId,
				tableGroupId,
				new TableGroupMessageRequestDto("stale auth", MessageType.TEXT, UUID.randomUUID())
		)).isInstanceOf(SoundConnectException.class)
				.hasFieldOrPropertyWithValue("errorType", ErrorType.FORBIDDEN_ACCESS);

		verify(rateLimitGuard).checkMessageUser(senderId, tableGroupId);
		verify(rateLimitGuard, never()).checkMessageTable(any());
		verify(messageRepository, never()).save(any());
	}
	
	// -------------------- getMessages --------------------

	@Test
	void sendMessageUsesReadCommittedSoPostLockReplaySeesTheOriginalCommit()
			throws NoSuchMethodException {
		Transactional transaction = TableGroupChatServiceImpl.class
				.getMethod(
						"sendMessage",
						UUID.class,
						UUID.class,
						TableGroupMessageRequestDto.class
				)
				.getAnnotation(Transactional.class);

		assertThat(transaction).isNotNull();
		assertThat(transaction.isolation()).isEqualTo(Isolation.READ_COMMITTED);
	}

	@Test
	void getMessagesUsesWritableRepeatableReadForGameSnapshotAndGhostVisibilityLocks()
			throws NoSuchMethodException {
		Transactional transaction = TableGroupChatServiceImpl.class
				.getMethod("getMessages", UUID.class, UUID.class, Pageable.class)
				.getAnnotation(Transactional.class);

		assertThat(transaction).isNotNull();
		assertThat(transaction.readOnly()).isFalse();
		assertThat(transaction.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
	}
	
	@Test
	void getMessages_whenRequesterAccepted_shouldResetUnreadAndReturnPage() {
		// given
		UUID tableGroupId = UUID.randomUUID();
		UUID requesterId = UUID.randomUUID();
		Pageable pageable = PageRequest.of(0, 20);
		
		TableGroup group = TableGroup.builder()
		                             .ownerId(UUID.randomUUID())
		                             .maxPersonCount(3)
		                             .genderPrefs(List.of("MALE", "FEMALE", "OTHER"))
		                             .ageMin(20)
		                             .ageMax(30)
		                             .expiresAt(Instant.now().plusSeconds(3_600))
		                             .status(TableGroupStatus.ACTIVE)
		                             .participants(new HashSet<>())
		                             .build();
		
		group.getParticipants().add(
				TableGroupParticipant.builder()
				                     .userId(requesterId)
				                     .status(ParticipantStatus.ACCEPTED)
				                     .joinedAt(Instant.now())
				                     .build()
		);
		
		when(tableGroupEntityFinder.GetTableGroupByTableGroupId(tableGroupId))
				.thenReturn(group);
		
		TableGroupMessage msg = TableGroupMessage.builder()
		                                         .tableGroupId(tableGroupId)
		                                         .senderId(UUID.randomUUID())
		                                         .content("selamlar")
		                                         .messageType(MessageType.TEXT)
		                                         .deletedAt(null)
		                                         .build();
		
		Page<TableGroupMessage> msgPage = new PageImpl<>(List.of(msg));
		when(messageRepository.findByTableGroupIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(tableGroupId, pageable))
				.thenReturn(msgPage);
		
		TableGroupMessageResponseDto dto = new TableGroupMessageResponseDto(
				UUID.randomUUID(),
				tableGroupId,
				msg.getSenderId(),
				msg.getContent(),
				msg.getMessageType(),
				Instant.now(),
				null
		);
		when(messageMapper.toResponseDto(msg)).thenReturn(dto);
		
		// when
		Page<TableGroupMessageResponseDto> result =
				chatService.getMessages(requesterId, tableGroupId, pageable);
		
		// then
		verify(unreadHelper).resetUnread(requesterId, tableGroupId);
		assertThat(result.getContent()).containsExactly(dto);
	}
	
	@Test
	void getMessages_whenRequesterNotAccepted_shouldThrowUnauthorized() {
		// given
		UUID tableGroupId = UUID.randomUUID();
		UUID requesterId = UUID.randomUUID();
		Pageable pageable = PageRequest.of(0, 20);
		
		TableGroup group = TableGroup.builder()
		                             .ownerId(UUID.randomUUID())
		                             .maxPersonCount(3)
		                             .genderPrefs(List.of("MALE", "FEMALE", "OTHER"))
		                             .ageMin(20)
		                             .ageMax(30)
		                             .expiresAt(Instant.now().plusSeconds(3_600))
		                             .status(TableGroupStatus.ACTIVE)
		                             .participants(new HashSet<>())
		                             .build();
		
		// requester participant listesinde yok
		when(tableGroupEntityFinder.GetTableGroupByTableGroupId(tableGroupId))
				.thenReturn(group);
		
		// when / then
		assertThatThrownBy(() -> chatService.getMessages(requesterId, tableGroupId, pageable))
				.isInstanceOf(SoundConnectException.class)
				.hasFieldOrPropertyWithValue("errorType", ErrorType.FORBIDDEN_ACCESS);
		
		verify(unreadHelper, never()).resetUnread(any(), any());
		verify(messageRepository, never()).findByTableGroupIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(any(), any());
	}

	@Test
	void getMessages_whenOlderPageRequested_shouldCapSizeIgnoreSortAndNotResetUnread() {
		UUID tableGroupId = UUID.randomUUID();
		UUID requesterId = UUID.randomUUID();
		UUID otherUserId = UUID.randomUUID();
		TableGroup group = createActiveGroupWithAcceptedParticipants(tableGroupId, requesterId, otherUserId);
		Pageable requested = PageRequest.of(1, 500, Sort.by("senderId").ascending());
		Pageable expected = PageRequest.of(1, 100);
		when(tableGroupEntityFinder.GetTableGroupByTableGroupId(tableGroupId)).thenReturn(group);
		when(messageRepository.findByTableGroupIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(
				tableGroupId,
				expected
		)).thenReturn(Page.empty(expected));

		Page<TableGroupMessageResponseDto> result =
				chatService.getMessages(requesterId, tableGroupId, requested);

		assertThat(result).isEmpty();
		verify(unreadHelper, never()).resetUnread(any(), any());
		verify(messageRepository).findByTableGroupIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(
				tableGroupId,
				expected
		);
	}

	@Test
	void getMessages_whenPageOffsetIsExcessive_shouldRejectBeforeQueryingMessages() {
		UUID tableGroupId = UUID.randomUUID();
		UUID requesterId = UUID.randomUUID();
		TableGroup group = createActiveGroupWithAcceptedParticipants(
				tableGroupId, requesterId, UUID.randomUUID());
		when(tableGroupEntityFinder.GetTableGroupByTableGroupId(tableGroupId)).thenReturn(group);

		assertThatThrownBy(() -> chatService.getMessages(
				requesterId, tableGroupId, PageRequest.of(1_001, 100)))
				.isInstanceOf(SoundConnectException.class)
				.hasFieldOrPropertyWithValue("errorType", ErrorType.TABLE_GROUP_PAGE_REQUEST_INVALID);
		verifyNoInteractions(messageRepository, unreadHelper);
	}
}
