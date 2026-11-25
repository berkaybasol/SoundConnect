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
import com.berkayb.soundconnect.modules.tablegroup.game.dto.response.TableGroupGameResponseDto;
import com.berkayb.soundconnect.modules.tablegroup.game.service.TableGroupGameProjectionService;
import com.berkayb.soundconnect.modules.tablegroup.enums.ParticipantStatus;
import com.berkayb.soundconnect.modules.tablegroup.enums.TableGroupStatus;
import com.berkayb.soundconnect.modules.tablegroup.observability.TableGroupMetrics;
import com.berkayb.soundconnect.modules.tablegroup.support.TableGroupEntityFinder;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.modules.tablegroup.game.service.TableGroupGameVisibility;
import com.berkayb.soundconnect.modules.user.block.UserBlockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class TableGroupChatServiceImpl implements TableGroupChatService {

	private static final int DEFAULT_PAGE_SIZE = 30;
	private static final int MAX_PAGE_SIZE = 100;
	private static final int MAX_PAGE_NUMBER = 1_000;
	private static final long MAX_MESSAGES_PER_TABLE_GROUP = 10_000L;

	private final TableGroupMessageRepository messageRepository;
	private final TableGroupMessageMapper messageMapper;
	private final TableGroupChatDelivery delivery;
	private final UserBlockService blocks;
	private final TableGroupEntityFinder tableGroupEntityFinder;
	private final TableGroupChatUnreadHelper unreadHelper;
	private final TableGroupRateLimitGuard rateLimitGuard;
	private final TableGroupMetrics metrics;
	private final TableGroupGameProjectionService gameProjectionService;
	private final com.berkayb.soundconnect.modules.user.support.AccountDeliveryFence accounts;

	/**
	 * Persists a message for an accepted participant. Cache and realtime side
	 * effects run only after the database commit succeeds, so clients cannot see
	 * a message that later rolls back.
	 */
	@Override
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public TableGroupMessageResponseDto sendMessage(
			UUID senderId,
			UUID tableGroupId,
			TableGroupMessageRequestDto requestDto
	) {
		MessageType messageType = requestDto.messageType() != null
				? requestDto.messageType()
				: MessageType.TEXT;
		if (messageType != MessageType.TEXT) {
			// SYSTEM is server-owned and IMAGE has no validated media contract yet.
			throw new SoundConnectException(
					ErrorType.VALIDATION_ERROR,
					"Kullanici mesajlari yalnizca TEXT tipinde olabilir"
			);
		}
		UUID clientMessageId = requestDto.clientMessageId();
		if (clientMessageId == null) {
			throw new SoundConnectException(
					ErrorType.VALIDATION_ERROR,
					"clientMessageId kullanici mesajlari icin zorunludur"
			);
		}
		String normalizedContent = requestDto.content().trim();
		// New-message inserts reference this actor; take its erasure fence before the table lock.
		accounts.requireActive(List.of(senderId));

		// A persisted row is its sender's durable application acknowledgement. It
		// must remain replayable even if Redis is unavailable, the original send
		// consumed the final quota slot, or the table lifecycle changed meanwhile.
		Optional<TableGroupMessageResponseDto> committedReplay = findReplay(
				tableGroupId,
				senderId,
				clientMessageId,
				normalizedContent,
				messageType
		);
		if (committedReplay.isPresent()) {
			return committedReplay.get();
		}

		// The transaction-scoped key lock is shared by every application node. If
		// the original request is still committing, an exact retry waits here and
		// then observes its row before touching Redis or the aggregate lock.
		messageRepository.acquireClientMessageKeyLock(
				tableGroupId,
				senderId,
				clientMessageId
		);
		Optional<TableGroupMessageResponseDto> serializedReplay = findReplay(
				tableGroupId,
				senderId,
				clientMessageId,
				normalizedContent,
				messageType
		);
		if (serializedReplay.isPresent()) {
			return serializedReplay.get();
		}

		// Random/new keys are bounded before they can contend on the shared table
		// row. The table-wide bucket is charged only after authoritative access is
		// checked under that row lock, so outsiders cannot drain shared capacity.
		rateLimitGuard.checkMessageUser(senderId, tableGroupId);

		TableGroup tableGroup = tableGroupEntityFinder.getTableGroupByIdForUpdate(tableGroupId);
		assertChatOpen(tableGroup);

		if (!isAcceptedParticipant(tableGroup, senderId)) {
			log.warn(
					"UNAUTHORIZED CHAT SEND attempt: userId={} tableGroupId={} status=DENIED",
					senderId,
					tableGroupId
			);
			throw new SoundConnectException(ErrorType.FORBIDDEN_ACCESS, "Bu masada konusma yetkin yok");
		}

		rateLimitGuard.checkMessageTable(tableGroupId);

		if (messageRepository.countByTableGroupIdAndDeletedAtIsNull(tableGroupId)
				>= MAX_MESSAGES_PER_TABLE_GROUP) {
			throw new SoundConnectException(ErrorType.TABLE_GROUP_CHAT_LIMIT_REACHED);
		}

		TableGroupMessage savedMessage = messageRepository.save(
				TableGroupMessage.builder()
						.tableGroupId(tableGroupId)
						.senderId(senderId)
						.clientMessageId(clientMessageId)
						.gameId(null)
						.content(normalizedContent)
						.messageType(messageType)
						.deletedAt(null)
						.build()
		);
		TableGroupMessageResponseDto response = messageMapper.toResponseDto(savedMessage);

		Set<UUID> unreadRecipients = TableGroupChatDelivery.acceptedUsers(tableGroup);

		runAfterCommit(() -> {
			recordMetric("message_sent", metrics::messageSent);
			delivery.enqueue(tableGroupId, response.messageId(), unreadRecipients);
		});
		return response;
	}

	/** Access, report and table identity are checked in the same transaction as this removal. */
	@Override
	@Transactional
	public void removeReportedMessage(UUID tableGroupId, UUID messageId) {
		TableGroup table = tableGroupEntityFinder.getTableGroupByIdForUpdate(tableGroupId);
		assertChatOpen(table);
		TableGroupMessage message = messageRepository.findById(messageId)
				.filter(row -> tableGroupId.equals(row.getTableGroupId()) && row.getDeletedAt() == null
						&& row.getMessageType() == MessageType.TEXT)
				.orElseThrow(() -> new SoundConnectException(ErrorType.TABLE_GROUP_REPORT_STATE_CHANGED));
		message.setDeletedAt(java.time.LocalDateTime.now(java.time.Clock.systemUTC()));
		messageRepository.saveAndFlush(message);
		runAfterCommit(() -> delivery.enqueue(tableGroupId, messageId, Set.of()));
	}

	private Optional<TableGroupMessageResponseDto> findReplay(
			UUID tableGroupId,
			UUID senderId,
			UUID clientMessageId,
			String normalizedContent,
			MessageType messageType
	) {
		return messageRepository.findByTableGroupIdAndSenderIdAndClientMessageId(
				tableGroupId,
				senderId,
				clientMessageId
		).map(persisted -> {
			if (!Objects.equals(persisted.getContent(), normalizedContent)
					|| persisted.getMessageType() != messageType) {
				throw new SoundConnectException(
						ErrorType.TABLE_GROUP_MESSAGE_IDEMPOTENCY_CONFLICT
				);
			}
			return messageMapper.toResponseDto(persisted);
		});
	}

	/**
	 * Returns newest messages first. Page zero is therefore always the most
	 * recent window; subsequent page numbers walk backwards in time.
	 */
	@Override
	// Game projections resolve Ghost identities under shared visibility locks;
	// PostgreSQL therefore requires a writable repeatable-read transaction.
	@Transactional(isolation = Isolation.REPEATABLE_READ)
	public Page<TableGroupMessageResponseDto> getMessages(
			UUID requesterId,
			UUID tableGroupId,
			Pageable pageable
	) {
		return getMessages(requesterId, tableGroupId, pageable, true, null);
	}

	@Override
	@Transactional(isolation = Isolation.REPEATABLE_READ)
	public Page<TableGroupMessageResponseDto> getMessages(UUID requesterId, UUID tableGroupId, Pageable pageable,
	                                                     boolean markRead, UUID expectedApplicationId) {
		TableGroup tableGroup = tableGroupEntityFinder.GetTableGroupByTableGroupId(tableGroupId);
		assertChatOpen(tableGroup);
		if (expectedApplicationId != null && tableGroup.getParticipants().stream().noneMatch(p ->
				requesterId.equals(p.getUserId()) && expectedApplicationId.equals(p.getApplicationId())
						&& p.getStatus() == ParticipantStatus.ACCEPTED)) {
			throw new SoundConnectException(ErrorType.FORBIDDEN_ACCESS);
		}
		if (!isAcceptedParticipant(tableGroup, requesterId)) {
			log.warn(
					"UNAUTHORIZED CHAT HISTORY READ attempt: userId={} tableGroupId={}",
					requesterId,
					tableGroupId
			);
			throw new SoundConnectException(ErrorType.FORBIDDEN_ACCESS, "Bu masanin sohbetine erisimin yok");
		}

		Pageable boundedPageable = boundedPageable(pageable);
		Page<TableGroupMessage> entityPage = messageRepository
				.findVisibleHistory(
						tableGroupId, requesterId,
						boundedPageable
				);
		List<UUID> gameIds = entityPage.getContent().stream()
				.map(TableGroupMessage::getGameId)
				.filter(Objects::nonNull)
				.distinct()
				.toList();
		Map<UUID, TableGroupGameResponseDto> games = gameIds.isEmpty() ? Map.of() : gameProjectionService.projectByIds(gameIds);
		Set<UUID> identities = TableGroupGameVisibility.identities(games.values());
		entityPage.stream().filter(message -> message.getGameId() != null)
				.map(TableGroupMessage::getSenderId).forEach(identities::add);
		Set<UUID> hidden = identities.isEmpty() ? Set.of() : blocks.blockedAmong(requesterId, identities);
		Page<TableGroupMessageResponseDto> result = entityPage.map(message -> {
			if (message.getGameId() == null) return messageMapper.toResponseDto(message);
			var game = Objects.requireNonNull(games.get(message.getGameId()), "Missing game for chat anchor");
			return TableGroupGameVisibility.restrict(messageMapper.toResponseDto(message, game), hidden);
		});
		if (markRead && boundedPageable.getPageNumber() == 0) {
			// Clear the derived badge only if the history transaction commits.
			runAfterCommit(() -> unreadHelper.resetUnread(requesterId, tableGroupId));
		}
		return result;
	}

	@Override
	@Transactional(readOnly = true)
	public int getUnreadBadge(UUID requesterId, UUID tableGroupId) {
		TableGroup tableGroup = tableGroupEntityFinder.GetTableGroupByTableGroupId(tableGroupId);
		assertChatOpen(tableGroup);
		if (!isAcceptedParticipant(tableGroup, requesterId)) {
			log.warn(
					"UNAUTHORIZED CHAT UNREAD READ attempt: userId={} tableGroupId={}",
					requesterId,
					tableGroupId
			);
			throw new SoundConnectException(
					ErrorType.FORBIDDEN_ACCESS,
					"Bu masanin sohbet badge'ine erisimin yok"
			);
		}
		return unreadHelper.getUnread(requesterId, tableGroupId);
	}

	private void assertChatOpen(TableGroup tableGroup) {
		if (tableGroup.getStatus() != TableGroupStatus.ACTIVE) {
			throw new SoundConnectException(
					ErrorType.TABLE_GROUP_NOT_FOUND,
					"Masa aktif degil veya iptal edildi"
			);
		}
		if (tableGroup.getExpiresAt() == null
				|| !tableGroup.getExpiresAt().isAfter(Instant.now())) {
			throw new SoundConnectException(
					ErrorType.TABLE_END_DATE_PASSED,
					"Masa suresi doldu, sohbet kullanilamaz"
			);
		}
	}

	private Pageable boundedPageable(Pageable pageable) {
		if (pageable == null || pageable.isUnpaged()) {
			return PageRequest.of(0, DEFAULT_PAGE_SIZE);
		}
		if (pageable.getPageNumber() < 0 || pageable.getPageNumber() > MAX_PAGE_NUMBER
				|| pageable.getPageSize() < 1) {
			throw new SoundConnectException(ErrorType.TABLE_GROUP_PAGE_REQUEST_INVALID);
		}
		return PageRequest.of(
				pageable.getPageNumber(),
				Math.min(pageable.getPageSize(), MAX_PAGE_SIZE)
		);
	}

	private boolean isAcceptedParticipant(TableGroup tableGroup, UUID userId) {
		return Optional.ofNullable(tableGroup.getParticipants()).orElseGet(Set::of).stream()
				.anyMatch(participant -> Objects.equals(participant.getUserId(), userId)
						&& participant.getStatus() == ParticipantStatus.ACCEPTED);
	}

	private void runAfterCommit(Runnable callback) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			// Keeps the component deterministic in unit tests and in the unlikely
			// event it is invoked outside a proxied transaction.
			callback.run();
			return;
		}

		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				callback.run();
			}
		});
	}

	private void recordMetric(String metricName, Runnable metricAction) {
		try {
			metricAction.run();
		} catch (RuntimeException metricsException) {
			log.debug(
					"TABLE-GROUP CHAT metric could not be recorded: metric={}, error={}",
					metricName,
					metricsException.toString()
			);
		}
	}
}
