package com.berkayb.soundconnect.modules.tablegroup.scheduler;

import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.tablegroup.chat.cache.TableGroupChatUnreadHelper;
import com.berkayb.soundconnect.modules.tablegroup.entity.TableGroup;
import com.berkayb.soundconnect.modules.tablegroup.entity.TableGroupParticipant;
import com.berkayb.soundconnect.modules.tablegroup.enums.ParticipantStatus;
import com.berkayb.soundconnect.modules.tablegroup.enums.TableGroupStatus;
import com.berkayb.soundconnect.modules.tablegroup.game.service.TableGroupGameLifecycleService;
import com.berkayb.soundconnect.modules.tablegroup.notification.outbox.TableGroupNotificationOutboxService;
import com.berkayb.soundconnect.modules.tablegroup.observability.TableGroupMetrics;
import com.berkayb.soundconnect.modules.tablegroup.repository.TableGroupRepository;
import com.berkayb.soundconnect.modules.tablegroup.support.TableGroupEntityFinder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Executes one table expiry in an independent transaction. */
@Service
@RequiredArgsConstructor
@Slf4j
public class TableGroupExpiryWorker {

	private final TableGroupEntityFinder tableGroupEntityFinder;
	private final TableGroupRepository tableGroupRepository;
	private final TableGroupNotificationOutboxService notificationOutboxService;
	private final TableGroupChatUnreadHelper unreadHelper;
	private final TableGroupGameLifecycleService gameLifecycleService;
	private final TableGroupMetrics metrics;

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public boolean expireIfDue(UUID tableGroupId, Instant scanTime) {
		TableGroup group = tableGroupEntityFinder.getTableGroupByIdForUpdate(tableGroupId);
		if (group.getStatus() != TableGroupStatus.ACTIVE
				|| group.getExpiresAt() == null
				|| group.getExpiresAt().isAfter(scanTime)) {
			return false;
		}

		List<UUID> recipientIds = acceptedParticipantIdsExcludingOwner(group);
		removePendingParticipants(group);
		gameLifecycleService.tableClosed(group, "TABLE_EXPIRED");
		group.setStatus(TableGroupStatus.INACTIVE);
		tableGroupRepository.save(group);

		for (UUID recipientId : recipientIds) {
			notificationOutboxService.enqueue(
					recipientId,
					NotificationType.TABLE_EXPIRED,
					"Masa süresi doldu",
					"Katıldığın masa etkinliğinin süresi doldu.",
					tablePayload(group.getId(), group.getOwnerId())
			);
		}
		runAfterCommit(() -> unreadHelper.clearAllUnreadForTableGroup(group.getId()));
		runAfterCommit(metrics::expired);

		log.debug(
				"TableGroup {} marked INACTIVE (expiredAt={}), notifications queued for accepted participants",
				group.getId(),
				group.getExpiresAt()
		);
		return true;
	}

	private List<UUID> acceptedParticipantIdsExcludingOwner(TableGroup tableGroup) {
		return Optional.ofNullable(tableGroup.getParticipants()).orElseGet(Set::of).stream()
				.filter(participant -> participant.getStatus() == ParticipantStatus.ACCEPTED)
				.map(TableGroupParticipant::getUserId)
				.filter(Objects::nonNull)
				.filter(userId -> !userId.equals(tableGroup.getOwnerId()))
				.distinct()
				.toList();
	}

	private void removePendingParticipants(TableGroup tableGroup) {
		Set<TableGroupParticipant> participants = tableGroup.getParticipants();
		if (participants != null) {
			participants.removeIf(participant -> participant.getStatus() == ParticipantStatus.PENDING);
		}
	}

	private Map<String, Object> tablePayload(UUID tableGroupId, UUID ownerId) {
		Map<String, Object> payload = new HashMap<>();
		payload.put("module", "TABLE");
		payload.put("action", "EXPIRED");
		payload.put("tableGroupId", tableGroupId.toString());
		if (ownerId != null) {
			payload.put("ownerId", ownerId.toString());
		}
		return payload;
	}

	private void runAfterCommit(Runnable callback) {
		Runnable safeCallback = () -> {
			try {
				callback.run();
			} catch (RuntimeException exception) {
				log.warn(
						"Table-group expiry after-commit side effect failed. exceptionType={}",
						exception.getClass().getName()
				);
			}
		};
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			safeCallback.run();
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				safeCallback.run();
			}
		});
	}
}
