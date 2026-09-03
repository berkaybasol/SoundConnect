package com.berkayb.soundconnect.modules.profile.ListenerProfile.support;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class ListenerVisibilityTimeProviderTest {

	@Test
	void nowUsesUtcClock() {
		Clock clock = Clock.fixed(Instant.parse("2026-09-03T21:15:30Z"), ZoneOffset.UTC);

		assertThat(new ListenerVisibilityTimeProvider(clock).now())
				.isEqualTo(LocalDateTime.of(2026, 9, 3, 21, 15, 30));
	}
}
