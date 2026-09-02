package com.berkayb.soundconnect.modules.tablegroup.notification.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.*;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component("tableGroupNotificationOutboxHealth")
@RequiredArgsConstructor
public class TableGroupNotificationOutboxHealthIndicator implements HealthIndicator {
	private static final List<TableGroupNotificationOutboxStatus> UNDELIVERED = List.of(
			TableGroupNotificationOutboxStatus.PENDING,
			TableGroupNotificationOutboxStatus.IN_FLIGHT
	);
	private final TableGroupNotificationOutboxRepository repository;
	private final TableGroupNotificationOutboxProperties properties;
	private final TableGroupNotificationOutboxTimeProvider timeProvider;

	@Override
	public Health health() {
		try {
			long pending = repository.countByStatus(TableGroupNotificationOutboxStatus.PENDING);
			long inFlight = repository.countByStatus(TableGroupNotificationOutboxStatus.IN_FLIGHT);
			long deadLetter = repository.countByStatus(TableGroupNotificationOutboxStatus.DEAD_LETTER);
			Instant oldest = repository.findOldestCreatedAtByStatusIn(UNDELIVERED).orElse(null);
			boolean stale = oldest != null
					&& !oldest.plus(properties.getHealthUndeliveredAgeThreshold()).isAfter(timeProvider.now());
			boolean degraded = deadLetter > 0 || stale;
			return Health.status(degraded ? "DEGRADED" : "UP")
					.withDetail("state", degraded ? "DEGRADED" : "UP")
					.withDetail("pending", pending)
					.withDetail("inFlight", inFlight)
					.withDetail("deadLetter", deadLetter)
					.withDetail("staleUndelivered", stale)
					.withDetail("oldestUndeliveredAt", oldest == null ? "none" : oldest)
					.build();
		} catch (Exception exception) {
			return Health.down(exception).withDetail("reason", exception.getClass().getSimpleName()).build();
		}
	}
}
