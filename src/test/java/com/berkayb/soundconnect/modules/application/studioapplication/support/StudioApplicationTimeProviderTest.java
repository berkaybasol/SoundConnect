package com.berkayb.soundconnect.modules.application.studioapplication.support;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class StudioApplicationTimeProviderTest {
	@Test
	void exposesTheInjectedInstantAsUtcWallClock() {
		Clock clock = Clock.fixed(Instant.parse("2026-08-03T09:15:30Z"), ZoneOffset.UTC);

		assertThat(new StudioApplicationTimeProvider(clock).nowUtc())
				.isEqualTo(LocalDateTime.of(2026, 8, 3, 9, 15, 30));
	}
}
