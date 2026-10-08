package com.berkayb.soundconnect.modules.studio.reservation.outbox;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@Slf4j
public class StudioReservationNotificationOutboxScheduler {
    private final StudioReservationNotificationOutboxService outboxService;
    private final StudioReservationNotificationOutboxDispatcher dispatcher;
    private final StudioReservationNotificationDispatchCoordinator dispatchCoordinator;
    private final StudioReservationNotificationOutboxProperties properties;

    public StudioReservationNotificationOutboxScheduler(
            StudioReservationNotificationOutboxService outboxService,
            StudioReservationNotificationOutboxDispatcher dispatcher,
            StudioReservationNotificationDispatchCoordinator dispatchCoordinator,
            StudioReservationNotificationOutboxProperties properties
    ) {
        this.outboxService = outboxService;
        this.dispatcher = dispatcher;
        this.dispatchCoordinator = dispatchCoordinator;
        this.properties = properties;
    }

    @Scheduled(
            fixedDelayString = "${app.notification.studio-reservation-outbox.poll-delay-ms:5000}",
            initialDelayString = "${app.notification.studio-reservation-outbox.initial-delay-ms:5000}"
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
                                "Studio reservation notification outbox dispatch task failed; lease recovery will retry. eventId={}, exceptionType={}",
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
                        "Studio reservation notification outbox executor rejected due event; row remains recoverable. eventId={}, exceptionType={}",
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

    @Scheduled(cron = "${app.notification.studio-reservation-outbox.cleanup-cron:0 50 4 * * *}")
    public void cleanupPublished() {
        int deleted = outboxService.cleanupPublished();
        if (deleted > 0) {
            log.info("Cleaned published Studio reservation notification outbox rows. deleted={}", deleted);
        }
    }
}
