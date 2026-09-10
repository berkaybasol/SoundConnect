package com.berkayb.soundconnect.modules.overthinking.outbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.actuate.health.Health;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OverthinkingNotificationOutboxHealthIndicatorTest {
	private static final Instant NOW = Instant.parse("2026-09-04T12:00:00Z");

	@Mock
	private OverthinkingNotificationOutboxRepository repository;

	@Mock
	private OverthinkingNotificationOutboxTimeProvider timeProvider;

	private OverthinkingNotificationOutboxHealthIndicator indicator;

	@BeforeEach
	void setUp() {
		OverthinkingNotificationOutboxProperties properties =
				new OverthinkingNotificationOutboxProperties();
		properties.setHealthUndeliveredAgeThreshold(Duration.ofMinutes(30));
		indicator = new OverthinkingNotificationOutboxHealthIndicator(repository, properties, timeProvider);
		lenient().when(timeProvider.now()).thenReturn(NOW);
	}

	@Test
	void reportsUpForFreshRecoverableWork() {
		queue(2, 1, 0, NOW.minus(Duration.ofMinutes(5)));

		Health health = indicator.health();

		assertThat(health.getStatus().getCode()).isEqualTo("UP");
		assertThat(health.getDetails()).containsEntry("staleUndelivered", false);
	}

	@Test
	void reportsDegradedWhenAnyDeadLetterExists() {
		queue(0, 0, 1, null);

		Health health = indicator.health();

		assertThat(health.getStatus().getCode()).isEqualTo("DEGRADED");
		assertThat(health.getDetails()).containsEntry("deadLetter", 1L);
	}

	@Test
	void reportsDegradedWhenUndeliveredWorkReachesAgeThreshold() {
		queue(1, 0, 0, NOW.minus(Duration.ofMinutes(30)));

		Health health = indicator.health();

		assertThat(health.getStatus().getCode()).isEqualTo("DEGRADED");
		assertThat(health.getDetails()).containsEntry("staleUndelivered", true);
	}

	private void queue(long pending, long inFlight, long deadLetter, Instant oldestUndeliveredAt) {
		when(repository.countByStatus(OverthinkingNotificationOutboxStatus.PENDING)).thenReturn(pending);
		when(repository.countByStatus(OverthinkingNotificationOutboxStatus.IN_FLIGHT)).thenReturn(inFlight);
		when(repository.countByStatus(OverthinkingNotificationOutboxStatus.DEAD_LETTER)).thenReturn(deadLetter);
		when(repository.findOldestCreatedAtByStatusIn(List.of(
				OverthinkingNotificationOutboxStatus.PENDING,
				OverthinkingNotificationOutboxStatus.IN_FLIGHT
		))).thenReturn(Optional.ofNullable(oldestUndeliveredAt));
	}
}
