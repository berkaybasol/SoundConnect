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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TableGroupExpiryWorkerTest {

	@Mock TableGroupEntityFinder tableGroupEntityFinder;
	@Mock TableGroupRepository tableGroupRepository;
	@Mock TableGroupNotificationOutboxService notificationOutboxService;
	@Mock TableGroupChatUnreadHelper unreadHelper;
	@Mock TableGroupGameLifecycleService gameLifecycleService;
	@Mock TableGroupMetrics metrics;
	@InjectMocks TableGroupExpiryWorker worker;

	@Test
	void expireIfDueUsesRequiresNewTransaction() throws NoSuchMethodException {
		Transactional transactional = TableGroupExpiryWorker.class
				.getMethod("expireIfDue", UUID.class, Instant.class)
				.getAnnotation(Transactional.class);

		assertThat(transactional).isNotNull();
		assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
	}

	@Test
	void expireIfDueTransitionsOneAggregateAndQueuesOnlyAcceptedNonOwnerRecipients() {
		UUID tableId = UUID.randomUUID();
		UUID ownerId = UUID.randomUUID();
		UUID acceptedId = UUID.randomUUID();
		TableGroupParticipant owner = participant(ownerId, ParticipantStatus.ACCEPTED);
		TableGroupParticipant accepted = participant(acceptedId, ParticipantStatus.ACCEPTED);
		TableGroupParticipant pending = participant(UUID.randomUUID(), ParticipantStatus.PENDING);
		Instant scanTime = Instant.parse("2026-09-02T10:00:00Z");
		TableGroup group = TableGroup.builder()
				.ownerId(ownerId)
				.status(TableGroupStatus.ACTIVE)
				.expiresAt(scanTime.minusSeconds(1))
				.participants(new HashSet<>(Set.of(owner, accepted, pending)))
				.build();
		group.setId(tableId);
		when(tableGroupEntityFinder.getTableGroupByIdForUpdate(tableId)).thenReturn(group);

		assertThat(worker.expireIfDue(tableId, scanTime)).isTrue();

		assertThat(group.getStatus()).isEqualTo(TableGroupStatus.INACTIVE);
		assertThat(group.getParticipants())
				.containsExactlyInAnyOrder(owner, accepted)
				.doesNotContain(pending);
		InOrder order = inOrder(tableGroupEntityFinder, gameLifecycleService, tableGroupRepository);
		order.verify(tableGroupEntityFinder).getTableGroupByIdForUpdate(tableId);
		order.verify(gameLifecycleService).tableClosed(group, "TABLE_EXPIRED");
		order.verify(tableGroupRepository).save(group);
		@SuppressWarnings("unchecked")
		ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
		verify(notificationOutboxService).enqueue(
				eq(acceptedId),
				eq(NotificationType.TABLE_EXPIRED),
				anyString(),
				anyString(),
				payload.capture()
		);
		assertThat(payload.getValue())
				.containsEntry("module", "TABLE")
				.containsEntry("action", "EXPIRED")
				.containsEntry("tableGroupId", tableId.toString())
				.containsEntry("ownerId", ownerId.toString());
		verify(unreadHelper).clearAllUnreadForTableGroup(tableId);
		verify(metrics).expired();
	}

	@Test
	void expireIfDueRechecksDueStateUnderLock() {
		UUID tableId = UUID.randomUUID();
		Instant scanTime = Instant.parse("2026-09-02T10:00:00Z");
		TableGroup group = TableGroup.builder()
				.status(TableGroupStatus.ACTIVE)
				.expiresAt(scanTime.plusSeconds(1))
				.build();
		group.setId(tableId);
		when(tableGroupEntityFinder.getTableGroupByIdForUpdate(tableId)).thenReturn(group);

		assertThat(worker.expireIfDue(tableId, scanTime)).isFalse();

		verifyNoInteractions(
				tableGroupRepository,
				notificationOutboxService,
				unreadHelper,
				gameLifecycleService,
				metrics
		);
	}

	@Test
	void expireIfDueDefersCacheAndMetricEffectsUntilCommit() {
		UUID tableId = UUID.randomUUID();
		Instant scanTime = Instant.parse("2026-09-02T10:00:00Z");
		TableGroup group = TableGroup.builder()
				.ownerId(UUID.randomUUID())
				.status(TableGroupStatus.ACTIVE)
				.expiresAt(scanTime.minusSeconds(1))
				.participants(new HashSet<>())
				.build();
		group.setId(tableId);
		when(tableGroupEntityFinder.getTableGroupByIdForUpdate(tableId)).thenReturn(group);

		TransactionSynchronizationManager.initSynchronization();
		try {
			assertThat(worker.expireIfDue(tableId, scanTime)).isTrue();
			verifyNoInteractions(unreadHelper, metrics);

			for (TransactionSynchronization synchronization
					: TransactionSynchronizationManager.getSynchronizations()) {
				synchronization.afterCommit();
			}
			verify(unreadHelper).clearAllUnreadForTableGroup(tableId);
			verify(metrics).expired();
		} finally {
			TransactionSynchronizationManager.clearSynchronization();
		}
	}

	@Test
	void expireIfDueRecordsMetricEvenWhenUnreadCleanupFailsAfterCommit() {
		UUID tableId = UUID.randomUUID();
		Instant scanTime = Instant.parse("2026-09-02T10:00:00Z");
		TableGroup group = TableGroup.builder()
				.ownerId(UUID.randomUUID())
				.status(TableGroupStatus.ACTIVE)
				.expiresAt(scanTime.minusSeconds(1))
				.participants(new HashSet<>())
				.build();
		group.setId(tableId);
		when(tableGroupEntityFinder.getTableGroupByIdForUpdate(tableId)).thenReturn(group);
		doThrow(new IllegalStateException("redis offline"))
				.when(unreadHelper).clearAllUnreadForTableGroup(tableId);

		assertThat(worker.expireIfDue(tableId, scanTime)).isTrue();

		verify(unreadHelper).clearAllUnreadForTableGroup(tableId);
		verify(metrics).expired();
	}

	private TableGroupParticipant participant(UUID userId, ParticipantStatus status) {
		return TableGroupParticipant.builder()
				.userId(userId)
				.status(status)
				.joinedAt(Instant.parse("2026-09-01T10:00:00Z"))
				.build();
	}
}
