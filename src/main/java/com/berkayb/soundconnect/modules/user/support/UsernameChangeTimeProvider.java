package com.berkayb.soundconnect.modules.user.support;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * Supplies UTC wall-clock values compatible with the existing LocalDateTime
 * persistence model while keeping username cooldown boundaries testable.
 */
@Component
public class UsernameChangeTimeProvider {
	private final Clock clock;

	public UsernameChangeTimeProvider() {
		this(Clock.systemUTC());
	}

	UsernameChangeTimeProvider(Clock clock) {
		this.clock = clock;
	}

	public LocalDateTime now() {
		return LocalDateTime.now(clock);
	}
}
