package com.berkayb.soundconnect.modules.user.support;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class UsernameChangeTimeProviderTest {

	@Test
	void nowUsesInjectedUtcClock() {
		Clock fixedClock = Clock.fixed(
				Instant.parse("2026-07-24T12:34:56Z"),
				ZoneOffset.UTC
		);

		UsernameChangeTimeProvider provider = new UsernameChangeTimeProvider(fixedClock);

		assertThat(provider.now())
				.isEqualTo(LocalDateTime.of(2026, 7, 24, 12, 34, 56));
	}
}
