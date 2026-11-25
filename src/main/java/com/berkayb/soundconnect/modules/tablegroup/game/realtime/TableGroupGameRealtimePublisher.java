package com.berkayb.soundconnect.modules.tablegroup.game.realtime;

import com.berkayb.soundconnect.modules.tablegroup.chat.service.TableGroupChatDelivery;
import com.berkayb.soundconnect.modules.tablegroup.chat.dto.response.TableGroupMessageResponseDto;
import com.berkayb.soundconnect.modules.tablegroup.chat.entity.TableGroupMessage;
import com.berkayb.soundconnect.modules.tablegroup.chat.mapper.TableGroupMessageMapper;
import com.berkayb.soundconnect.modules.tablegroup.entity.TableGroup;
import com.berkayb.soundconnect.modules.tablegroup.game.dto.response.TableGroupGameResponseDto;
import com.berkayb.soundconnect.modules.tablegroup.observability.TableGroupMetrics;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Set;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class TableGroupGameRealtimePublisher {
	private final TableGroupMessageMapper messageMapper;
	private final TableGroupChatDelivery delivery;
	private final TableGroupMetrics metrics;

	public TableGroupMessageResponseDto publishCreatedAfterCommit(
			TableGroup tableGroup,
			TableGroupMessage message,
			TableGroupGameResponseDto game
	) {
		TableGroupMessageResponseDto response = messageMapper.toResponseDto(message, game);
		Set<UUID> recipients = TableGroupChatDelivery.acceptedUsers(tableGroup);
		runAfterCommit(() -> {
			safeMetric(metrics::messageSent);
			safeMetric(metrics::gameCreated);
			delivery.enqueue(tableGroup.getId(), response.messageId(), recipients);
		});
		return response;
	}

	public TableGroupMessageResponseDto publishUpdatedAfterCommit(
			UUID tableGroupId,
			TableGroupMessage message,
			TableGroupGameResponseDto game
	) {
		TableGroupMessageResponseDto response = messageMapper.toResponseDto(message, game);
		runAfterCommit(() -> delivery.enqueue(tableGroupId, response.messageId(), Set.of()));
		return response;
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
