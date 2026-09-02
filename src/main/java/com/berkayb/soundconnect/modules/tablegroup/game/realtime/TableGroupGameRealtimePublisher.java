package com.berkayb.soundconnect.modules.tablegroup.game.realtime;

import com.berkayb.soundconnect.modules.tablegroup.chat.cache.TableGroupChatUnreadHelper;
import com.berkayb.soundconnect.modules.tablegroup.chat.dto.response.TableGroupMessageResponseDto;
import com.berkayb.soundconnect.modules.tablegroup.chat.entity.TableGroupMessage;
import com.berkayb.soundconnect.modules.tablegroup.chat.mapper.TableGroupMessageMapper;
import com.berkayb.soundconnect.modules.tablegroup.entity.TableGroup;
import com.berkayb.soundconnect.modules.tablegroup.enums.ParticipantStatus;
import com.berkayb.soundconnect.modules.tablegroup.game.dto.response.TableGroupGameResponseDto;
import com.berkayb.soundconnect.modules.tablegroup.observability.TableGroupMetrics;
import com.berkayb.soundconnect.shared.realtime.WebSocketChannels;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.*;

@Component
@RequiredArgsConstructor
@Slf4j
public class TableGroupGameRealtimePublisher {
	private final TableGroupMessageMapper messageMapper;
	private final SimpMessagingTemplate messagingTemplate;
	private final TableGroupChatUnreadHelper unreadHelper;
	private final TableGroupMetrics metrics;

	public TableGroupMessageResponseDto publishCreatedAfterCommit(
			TableGroup tableGroup,
			TableGroupMessage message,
			TableGroupGameResponseDto game
	) {
		TableGroupMessageResponseDto response = messageMapper.toResponseDto(message, game);
		UUID senderId = message.getSenderId();
		List<UUID> recipients = Optional.ofNullable(tableGroup.getParticipants()).orElseGet(Set::of)
				.stream()
				.filter(participant -> participant.getStatus() == ParticipantStatus.ACCEPTED)
				.map(participant -> participant.getUserId())
				.filter(Objects::nonNull)
				.filter(userId -> !userId.equals(senderId))
				.distinct()
				.toList();
		runAfterCommit(() -> {
			for (UUID recipient : recipients) {
				try {
					unreadHelper.incrementUnread(recipient, tableGroup.getId());
				} catch (RuntimeException exception) {
					safeMetric(() -> metrics.unreadCacheFailed("game_delivery"));
					log.warn(
							"TABLE-GROUP GAME unread delivery failed: userId={}, tableGroupId={}, error={}",
							recipient,
							tableGroup.getId(),
							exception.toString()
					);
				}
			}
			safeMetric(metrics::messageSent);
			safeMetric(metrics::gameCreated);
			publish(tableGroup.getId(), response);
		});
		return response;
	}

	public TableGroupMessageResponseDto publishUpdatedAfterCommit(
			UUID tableGroupId,
			TableGroupMessage message,
			TableGroupGameResponseDto game
	) {
		TableGroupMessageResponseDto response = messageMapper.toResponseDto(message, game);
		runAfterCommit(() -> publish(tableGroupId, response));
		return response;
	}

	private void publish(UUID tableGroupId, TableGroupMessageResponseDto response) {
		try {
			messagingTemplate.convertAndSend(WebSocketChannels.tableGroup(tableGroupId), response);
		} catch (RuntimeException exception) {
			safeMetric(metrics::realtimePublishFailed);
			log.warn(
					"TABLE-GROUP GAME realtime publish failed: tableGroupId={}, gameId={}, error={}",
					tableGroupId,
					response.game() == null ? null : response.game().gameId(),
					exception.toString()
			);
		}
	}

	private void safeMetric(Runnable metric) {
		try {
			metric.run();
		} catch (RuntimeException ignored) {
			// Metrics must never affect a committed game transition.
		}
	}

	private void runAfterCommit(Runnable callback) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
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
}
