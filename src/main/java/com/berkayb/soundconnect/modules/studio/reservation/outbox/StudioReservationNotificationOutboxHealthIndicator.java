package com.berkayb.soundconnect.modules.studio.reservation.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component("studioReservationNotificationOutboxHealth")
@RequiredArgsConstructor
public class StudioReservationNotificationOutboxHealthIndicator implements HealthIndicator {
    private static final List<StudioReservationNotificationOutboxStatus> UNDELIVERED_STATUSES = List.of(
            StudioReservationNotificationOutboxStatus.PENDING,
            StudioReservationNotificationOutboxStatus.IN_FLIGHT
    );

    private final StudioReservationNotificationOutboxRepository repository;
    private final StudioReservationNotificationOutboxProperties properties;
    private final StudioReservationNotificationOutboxTimeProvider timeProvider;

    @Override
    public Health health() {
        try {
            long pending = repository.countByStatus(StudioReservationNotificationOutboxStatus.PENDING);
            long inFlight = repository.countByStatus(StudioReservationNotificationOutboxStatus.IN_FLIGHT);
            long deadLetter = repository.countByStatus(StudioReservationNotificationOutboxStatus.DEAD_LETTER);
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
