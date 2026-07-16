package com.berkayb.soundconnect.shared.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Enables JPA audit timestamps and generates them as UTC wall-clock values.
 * Base entities currently store LocalDateTime, so using a single explicit zone
 * avoids environment-dependent offsets until those columns migrate to Instant.
 */
@Configuration
@EnableJpaAuditing(dateTimeProviderRef = "utcDateTimeProvider")
public class JpaAuditingConfig {

	@Bean
	DateTimeProvider utcDateTimeProvider() {
		Clock clock = Clock.systemUTC();
		return () -> Optional.of(LocalDateTime.now(clock));
	}
}
