package com.berkayb.soundconnect.modules.tablegroup.notification.outbox;

import com.berkayb.soundconnect.modules.tablegroup.scheduler.TableGroupSchedulingConfiguration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@Slf4j
public class TableGroupNotificationOutboxScheduler {
	private final TableGroupNotificationOutboxService outboxService;
	private final TableGroupNotificationOutboxDispatcher dispatcher;
	private final TableGroupNotificationDispatchCoordinator coordinator;
	private final TableGroupNotificationOutboxProperties properties;

	public TableGroupNotificationOutboxScheduler(
			TableGroupNotificationOutboxService outboxService,
			TableGroupNotificationOutboxDispatcher dispatcher,
			TableGroupNotificationDispatchCoordinator coordinator,
			TableGroupNotificationOutboxProperties properties
	) {
		this.outboxService = outboxService;
		this.dispatcher = dispatcher;
		this.coordinator = coordinator;
		this.properties = properties;
	}

	@Scheduled(
			fixedDelayString = "${app.notification.table-group-outbox.poll-delay-ms:5000}",
			initialDelayString = "${app.notification.table-group-outbox.initial-delay-ms:5000}",
			scheduler = TableGroupSchedulingConfiguration.OUTBOX_SCHEDULER
	)
	public void dispatchDue() {
		int batchSize = properties.getBatchSize();
		int scheduledCount = coordinator.scheduledCount();
		int candidateLimit = scheduledCount > Integer.MAX_VALUE - batchSize
				? Integer.MAX_VALUE
				: batchSize + scheduledCount;
		int scheduled = 0;
		for (UUID eventId : outboxService.findDueEventIds(candidateLimit)) {
			if (scheduled >= batchSize) break;
			try {
				if (coordinator.trySchedule(eventId, () -> dispatchSafely(eventId))) scheduled++;
			} catch (RuntimeException exception) {
				log.warn("TableGroup outbox executor rejected event; row remains due. eventId={}, exceptionType={}",
						eventId, exception.getClass().getName());
				break;
			}
		}
	}

	private void dispatchSafely(UUID eventId) {
		try {
			dispatcher.dispatch(eventId);
		} catch (RuntimeException exception) {
			log.warn("TableGroup outbox dispatch task failed; lease recovery will retry. eventId={}, exceptionType={}",
					eventId, exception.getClass().getName());
		}
	}

	@Scheduled(
			cron = "${app.notification.table-group-outbox.cleanup-cron:0 40 4 * * *}",
			scheduler = TableGroupSchedulingConfiguration.OUTBOX_SCHEDULER
	)
	public void cleanupPublished() {
		int deleted = outboxService.cleanupPublished();
		if (deleted > 0) log.info("Cleaned published TableGroup notification outbox rows. deleted={}", deleted);
	}
}
