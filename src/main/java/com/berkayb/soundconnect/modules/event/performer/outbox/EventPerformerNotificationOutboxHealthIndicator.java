package com.berkayb.soundconnect.modules.event.performer.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component("eventPerformerNotificationOutboxHealth")
@RequiredArgsConstructor
public class EventPerformerNotificationOutboxHealthIndicator implements HealthIndicator {
	private static final List<EventPerformerNotificationOutboxStatus> UNDELIVERED_STATUSES = List.of(
			EventPerformerNotificationOutboxStatus.PENDING,
			EventPerformerNotificationOutboxStatus.IN_FLIGHT
	);

	private final EventPerformerNotificationOutboxRepository repository;
	private final EventPerformerNotificationOutboxProperties properties;
	private final EventPerformerNotificationOutboxTimeProvider timeProvider;

	@Override
	public Health health() {
		try {
			long pending = repository.countByStatus(EventPerformerNotificationOutboxStatus.PENDING);
			long inFlight = repository.countByStatus(EventPerformerNotificationOutboxStatus.IN_FLIGHT);
			long deadLetter = repository.countByStatus(EventPerformerNotificationOutboxStatus.DEAD_LETTER);
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
					.withDetail(
							"oldestUndeliveredAt",
							oldestUndeliveredAt == null ? "none" : oldestUndeliveredAt
					)
					.build();
		} catch (Exception exception) {
			return Health.unknown()
					.withDetail("reason", exception.getClass().getSimpleName())
					.build();
		}
	}
}
