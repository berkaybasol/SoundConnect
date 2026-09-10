package com.berkayb.soundconnect.modules.overthinking.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component("overthinkingNotificationOutboxHealth")
@RequiredArgsConstructor
public class OverthinkingNotificationOutboxHealthIndicator implements HealthIndicator {
	private static final List<OverthinkingNotificationOutboxStatus> UNDELIVERED_STATUSES = List.of(
			OverthinkingNotificationOutboxStatus.PENDING,
			OverthinkingNotificationOutboxStatus.IN_FLIGHT
	);

	private final OverthinkingNotificationOutboxRepository repository;
	private final OverthinkingNotificationOutboxProperties properties;
	private final OverthinkingNotificationOutboxTimeProvider timeProvider;

	@Override
	public Health health() {
		try {
			long pending = repository.countByStatus(OverthinkingNotificationOutboxStatus.PENDING);
			long inFlight = repository.countByStatus(OverthinkingNotificationOutboxStatus.IN_FLIGHT);
			long deadLetter = repository.countByStatus(OverthinkingNotificationOutboxStatus.DEAD_LETTER);
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
