package com.berkayb.soundconnect.modules.overthinking.outbox;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

@Component
public class OverthinkingNotificationOutboxTimeProvider {
	private final Clock clock;

	public OverthinkingNotificationOutboxTimeProvider() {
		this(Clock.systemUTC());
	}

	OverthinkingNotificationOutboxTimeProvider(Clock clock) {
		this.clock = clock;
	}

	public Instant now() {
		return Instant.now(clock);
	}
}
