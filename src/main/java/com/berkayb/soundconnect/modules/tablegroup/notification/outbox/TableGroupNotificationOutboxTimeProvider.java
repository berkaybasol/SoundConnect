package com.berkayb.soundconnect.modules.tablegroup.notification.outbox;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

@Component
public class TableGroupNotificationOutboxTimeProvider {
	private final Clock clock;

	public TableGroupNotificationOutboxTimeProvider() {
		this(Clock.systemUTC());
	}

	TableGroupNotificationOutboxTimeProvider(Clock clock) {
		this.clock = clock;
	}

	public Instant now() {
		return Instant.now(clock);
	}
}
