package com.berkayb.soundconnect.modules.collab.outbox;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@Slf4j
public class CollabNotificationOutboxScheduler {
    private final CollabNotificationOutboxService outboxService;
    private final CollabNotificationOutboxDispatcher dispatcher;
    private final CollabNotificationDispatchCoordinator dispatchCoordinator;
    private final CollabNotificationOutboxProperties properties;

    public CollabNotificationOutboxScheduler(
            CollabNotificationOutboxService outboxService,
            CollabNotificationOutboxDispatcher dispatcher,
            CollabNotificationDispatchCoordinator dispatchCoordinator,
            CollabNotificationOutboxProperties properties
    ) {
        this.outboxService = outboxService;
        this.dispatcher = dispatcher;
        this.dispatchCoordinator = dispatchCoordinator;
        this.properties = properties;
    }

    @Scheduled(
            fixedDelayString = "${app.notification.collab-outbox.poll-delay-ms:5000}",
            initialDelayString = "${app.notification.collab-outbox.initial-delay-ms:5000}"
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
                boolean accepted = dispatchCoordinator.trySchedule(eventId, () -> {
                    try {
                        dispatcher.dispatch(eventId);
                    } catch (RuntimeException exception) {
                        log.warn(
                                "Collab notification outbox dispatch task failed; lease recovery will retry. eventId={}, exceptionType={}",
                                eventId, exception.getClass().getName()
                        );
                    }
                });
                if (accepted) {
                    newlyScheduled++;
                }
            } catch (RuntimeException exception) {
                // No row is claimed until the task begins. Leaving this event due
                // lets the next scheduler tick or another node recover it.
                log.warn(
                        "Collab notification outbox executor rejected due event; row remains recoverable. eventId={}, exceptionType={}",
                        eventId, exception.getClass().getName()
                );
                break;
            }
        }
    }

    private static int candidateLimit(int batchSize, int scheduledCount) {
        if (scheduledCount > Integer.MAX_VALUE - batchSize) {
            return Integer.MAX_VALUE;
        }
        return batchSize + scheduledCount;
    }

    @Scheduled(cron = "${app.notification.collab-outbox.cleanup-cron:0 35 4 * * *}")
    public void cleanupPublished() {
        int deleted = outboxService.cleanupPublished();
        if (deleted > 0) {
            log.info("Cleaned published Collab notification outbox rows. deleted={}", deleted);
        }
    }
}
