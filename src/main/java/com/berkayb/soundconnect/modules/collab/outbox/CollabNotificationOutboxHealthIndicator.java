package com.berkayb.soundconnect.modules.collab.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component("collabNotificationOutboxHealth")
@RequiredArgsConstructor
public class CollabNotificationOutboxHealthIndicator implements HealthIndicator {
    private static final List<CollabNotificationOutboxStatus> UNDELIVERED_STATUSES = List.of(
            CollabNotificationOutboxStatus.PENDING,
            CollabNotificationOutboxStatus.IN_FLIGHT
    );

    private final CollabNotificationOutboxRepository repository;
    private final CollabNotificationOutboxProperties properties;
    private final CollabNotificationOutboxTimeProvider timeProvider;

    @Override
    public Health health() {
        try {
            long pending = repository.countByStatus(CollabNotificationOutboxStatus.PENDING);
            long inFlight = repository.countByStatus(CollabNotificationOutboxStatus.IN_FLIGHT);
            long deadLetter = repository.countByStatus(CollabNotificationOutboxStatus.DEAD_LETTER);
            Instant oldestUndeliveredAt = repository
                    .findOldestCreatedAtByStatusIn(UNDELIVERED_STATUSES)
                    .orElse(null);
            boolean staleUndelivered = oldestUndeliveredAt != null
                    && !oldestUndeliveredAt.plus(properties.getHealthUndeliveredAgeThreshold())
                    .isAfter(timeProvider.now());
            boolean degraded = deadLetter > 0 || staleUndelivered;

            return Health.status(degraded ? "DEGRADED" : "UP")
                    .withDetail("state", degraded ? "DEGRADED" : "UP")
                    .withDetail("pending", pending)
                    .withDetail("inFlight", inFlight)
                    .withDetail("deadLetter", deadLetter)
                    .withDetail("staleUndelivered", staleUndelivered)
                    .withDetail("oldestUndeliveredAt",
                            oldestUndeliveredAt == null ? "none" : oldestUndeliveredAt)
                    .build();
        } catch (Exception exception) {
            return Health.unknown()
                    .withDetail("reason", exception.getClass().getSimpleName())
                    .build();
        }
    }
}
