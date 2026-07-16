package com.berkayb.soundconnect.modules.media.storage;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PresignedUploadWriteWindowTest {

	private static final Instant NOW = Instant.parse("2026-07-15T12:00:00Z");
	private final PresignedUploadWriteWindow window = new PresignedUploadWriteWindow(
			900, Duration.ofMinutes(1), Clock.fixed(NOW, ZoneOffset.UTC));

	@Test
	void legacyFallbackUsesMaximumSupportedTtlAndSkew() {
		Duration legacyMaximum = Duration.ofDays(7).plusMinutes(75);
		LocalDateTime maximumWindowAgo = LocalDateTime.ofInstant(
				NOW.minus(legacyMaximum), ZoneOffset.UTC);
		LocalDateTime almostMaximumWindowAgo = LocalDateTime.ofInstant(
				NOW.minus(legacyMaximum).plusNanos(1), ZoneOffset.UTC);

		assertThat(window.isSafeToDelete(maximumWindowAgo)).isTrue();
		assertThat(window.isSafeToDelete(almostMaximumWindowAgo)).isFalse();
	}

	@Test
	void exactPersistedDeadlineUsesSigningConfigurationWithoutLaterInference() {
		LocalDateTime deadline = window.deadlineForNewSignature();

		assertThat(deadline).isEqualTo(LocalDateTime.ofInstant(
				NOW.plus(Duration.ofMinutes(16)), ZoneOffset.UTC));
		assertThat(window.isSafeToDelete(
				LocalDateTime.ofInstant(NOW.minus(Duration.ofHours(24)), ZoneOffset.UTC), null)).isTrue();
		assertThat(window.isSafeToDelete(
				LocalDateTime.ofInstant(NOW.minus(Duration.ofHours(24)).plusNanos(1), ZoneOffset.UTC), null)).isFalse();
	}

	@Test
	void completionGraceCannotBeConfiguredBelowSafetyFloor() {
		assertThatThrownBy(() -> new PresignedUploadWriteWindow(
				900, Duration.ofMinutes(1), Duration.ofHours(23),
				Clock.fixed(NOW, ZoneOffset.UTC)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("PT24H");
	}

	@Test
	void missingAuditTimestampFailsClosed() {
		assertThat(window.isSafeToDelete(null)).isFalse();
		assertThatThrownBy(() -> window.notBefore(null))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
