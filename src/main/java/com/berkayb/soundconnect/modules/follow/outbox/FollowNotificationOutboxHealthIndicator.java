package com.berkayb.soundconnect.modules.follow.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component("followNotificationOutboxHealth")
@RequiredArgsConstructor
public class FollowNotificationOutboxHealthIndicator implements HealthIndicator {
    private static final List<FollowNotificationOutboxStatus> UNDELIVERED_STATUSES = List.of(
            FollowNotificationOutboxStatus.PENDING,
            FollowNotificationOutboxStatus.IN_FLIGHT
    );

    private final FollowNotificationOutboxRepository repository;
    private final FollowNotificationOutboxProperties properties;
    private final FollowNotificationOutboxTimeProvider timeProvider;

    @Override
    public Health health() {
        try {
            long pending = repository.countByStatus(FollowNotificationOutboxStatus.PENDING);
            long inFlight = repository.countByStatus(FollowNotificationOutboxStatus.IN_FLIGHT);
            long deadLetter = repository.countByStatus(FollowNotificationOutboxStatus.DEAD_LETTER);
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
                    .withDetail("suppressed", repository.countByStatus(FollowNotificationOutboxStatus.SUPPRESSED))
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
