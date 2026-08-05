package com.berkayb.soundconnect.modules.application.studioapplication.support;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Component
public class StudioApplicationTimeProvider {
	private final Clock clock;

	public StudioApplicationTimeProvider() {
		this(Clock.systemUTC());
	}

	StudioApplicationTimeProvider(Clock clock) {
		this.clock = clock;
	}

	public LocalDateTime nowUtc() {
		return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
	}
}
