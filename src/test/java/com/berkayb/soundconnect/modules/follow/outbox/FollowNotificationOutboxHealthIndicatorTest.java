package com.berkayb.soundconnect.modules.follow.outbox;

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
class FollowNotificationOutboxHealthIndicatorTest {
	private static final Instant NOW = Instant.parse("2026-09-04T12:00:00Z");

	@Mock
	private FollowNotificationOutboxRepository repository;

	@Mock
	private FollowNotificationOutboxTimeProvider timeProvider;

	private FollowNotificationOutboxHealthIndicator indicator;

	@BeforeEach
	void setUp() {
		FollowNotificationOutboxProperties properties =
				new FollowNotificationOutboxProperties();
		properties.setHealthUndeliveredAgeThreshold(Duration.ofMinutes(30));
		indicator = new FollowNotificationOutboxHealthIndicator(repository, properties, timeProvider);
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
		when(repository.countByStatus(FollowNotificationOutboxStatus.PENDING)).thenReturn(pending);
		when(repository.countByStatus(FollowNotificationOutboxStatus.IN_FLIGHT)).thenReturn(inFlight);
		when(repository.countByStatus(FollowNotificationOutboxStatus.DEAD_LETTER)).thenReturn(deadLetter);
		when(repository.findOldestCreatedAtByStatusIn(List.of(
				FollowNotificationOutboxStatus.PENDING,
				FollowNotificationOutboxStatus.IN_FLIGHT
		))).thenReturn(Optional.ofNullable(oldestUndeliveredAt));
	}
}
