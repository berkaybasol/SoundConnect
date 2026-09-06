package com.berkayb.soundconnect.modules.event.performer.outbox;

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
class EventPerformerNotificationOutboxHealthIndicatorTest {
	private static final Instant NOW = Instant.parse("2026-09-04T12:00:00Z");

	@Mock
	private EventPerformerNotificationOutboxRepository repository;

	@Mock
	private EventPerformerNotificationOutboxTimeProvider timeProvider;

	private EventPerformerNotificationOutboxHealthIndicator indicator;

	@BeforeEach
	void setUp() {
		EventPerformerNotificationOutboxProperties properties =
				new EventPerformerNotificationOutboxProperties();
		properties.setHealthUndeliveredAgeThreshold(Duration.ofMinutes(30));
		indicator = new EventPerformerNotificationOutboxHealthIndicator(repository, properties, timeProvider);
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
		when(repository.countByStatus(EventPerformerNotificationOutboxStatus.PENDING)).thenReturn(pending);
		when(repository.countByStatus(EventPerformerNotificationOutboxStatus.IN_FLIGHT)).thenReturn(inFlight);
		when(repository.countByStatus(EventPerformerNotificationOutboxStatus.DEAD_LETTER)).thenReturn(deadLetter);
		when(repository.findOldestCreatedAtByStatusIn(List.of(
				EventPerformerNotificationOutboxStatus.PENDING,
				EventPerformerNotificationOutboxStatus.IN_FLIGHT
		))).thenReturn(Optional.ofNullable(oldestUndeliveredAt));
	}
}
