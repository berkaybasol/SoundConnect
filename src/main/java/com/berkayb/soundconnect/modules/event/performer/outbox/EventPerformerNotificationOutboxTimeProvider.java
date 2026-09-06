package com.berkayb.soundconnect.modules.event.performer.outbox;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

@Component
public class EventPerformerNotificationOutboxTimeProvider {
	private final Clock clock;

	public EventPerformerNotificationOutboxTimeProvider() {
		this(Clock.systemUTC());
	}

	EventPerformerNotificationOutboxTimeProvider(Clock clock) {
		this.clock = clock;
	}

	public Instant now() {
		return Instant.now(clock);
	}
}
