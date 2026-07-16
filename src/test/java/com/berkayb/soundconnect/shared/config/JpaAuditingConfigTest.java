package com.berkayb.soundconnect.shared.config;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class JpaAuditingConfigTest {

	@Test
	void auditingTimestampsAreGeneratedInUtc() {
		var provider = new JpaAuditingConfig().utcDateTimeProvider();
		var value = provider.getNow().orElseThrow();

		assertThat(value).isInstanceOf(LocalDateTime.class);
		assertThat((LocalDateTime) value)
				.isCloseTo(LocalDateTime.now(Clock.systemUTC()), within(1, ChronoUnit.SECONDS));
	}
}
