package com.berkayb.soundconnect.modules.overthinking.outbox;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@Slf4j
public class OverthinkingNotificationOutboxScheduler {
	private final OverthinkingNotificationOutboxService outboxService;
	private final OverthinkingNotificationOutboxDispatcher dispatcher;
	private final OverthinkingNotificationDispatchCoordinator dispatchCoordinator;
	private final OverthinkingNotificationOutboxProperties properties;

	public OverthinkingNotificationOutboxScheduler(
			OverthinkingNotificationOutboxService outboxService,
			OverthinkingNotificationOutboxDispatcher dispatcher,
			OverthinkingNotificationDispatchCoordinator dispatchCoordinator,
			OverthinkingNotificationOutboxProperties properties
	) {
		this.outboxService = outboxService;
		this.dispatcher = dispatcher;
		this.dispatchCoordinator = dispatchCoordinator;
		this.properties = properties;
	}

	@Scheduled(
			fixedDelayString = "${app.notification.overthinking-outbox.poll-delay-ms:5000}",
			initialDelayString = "${app.notification.overthinking-outbox.initial-delay-ms:5000}"
	)
	public void dispatchDue() {
		int batchSize = properties.getBatchSize();
		int candidateLimit = candidateLimit(batchSize, dispatchCoordinator.scheduledCount());
		int newlyScheduled = 0;
		for (UUID eventId : outboxService.findDueEventIds(candidateLimit)) {
			if (newlyScheduled >= batchSize) {
				break;
			}
			try {
				boolean accepted = dispatchCoordinator.trySchedule(eventId, () -> dispatchSafely(eventId));
				if (accepted) {
					newlyScheduled++;
				}
			} catch (RuntimeException exception) {
				log.warn(
						"Overthinking notification outbox executor rejected due event; row remains recoverable. eventId={}, exceptionType={}",
						eventId, exception.getClass().getName()
				);
				break;
			}
		}
	}

	@Scheduled(cron = "${app.notification.overthinking-outbox.cleanup-cron:0 50 4 * * *}")
	public void cleanupPublished() {
		int deleted = outboxService.cleanupPublished();
		if (deleted > 0) {
			log.info("Cleaned published overthinking notification outbox rows. deleted={}", deleted);
		}
	}

	private void dispatchSafely(UUID eventId) {
		try {
			dispatcher.dispatch(eventId);
		} catch (RuntimeException exception) {
			log.warn(
					"Overthinking notification outbox dispatch task failed; lease recovery will retry. eventId={}, exceptionType={}",
					eventId, exception.getClass().getName()
			);
		}
	}

	private static int candidateLimit(int batchSize, int scheduledCount) {
		if (scheduledCount > Integer.MAX_VALUE - batchSize) {
			return Integer.MAX_VALUE;
		}
		return batchSize + scheduledCount;
	}
}
