package com.berkayb.soundconnect.modules.collab.outbox;

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
class CollabNotificationOutboxHealthIndicatorTest {

    private static final Instant NOW = Instant.parse("2026-08-11T12:00:00Z");

    @Mock
    private CollabNotificationOutboxRepository repository;

    @Mock
    private CollabNotificationOutboxTimeProvider timeProvider;

    private CollabNotificationOutboxHealthIndicator indicator;

    @BeforeEach
    void setUp() {
        CollabNotificationOutboxProperties properties = new CollabNotificationOutboxProperties();
        properties.setHealthUndeliveredAgeThreshold(Duration.ofMinutes(30));
        indicator = new CollabNotificationOutboxHealthIndicator(repository, properties, timeProvider);
        lenient().when(timeProvider.now()).thenReturn(NOW);
    }

    @Test
    void reportsUpWhenQueueHasNoDeadLettersOrStalePendingWork() {
        queue(2, 1, 0, NOW.minus(Duration.ofMinutes(5)));

        Health health = indicator.health();

        assertThat(health.getStatus().getCode()).isEqualTo("UP");
        assertThat(health.getDetails()).containsEntry("staleUndelivered", false);
    }

    @Test
    void reportsAlertableDegradedStatusWhenDeadLettersExist() {
        queue(0, 0, 1, null);

        Health health = indicator.health();

        assertThat(health.getStatus().getCode()).isEqualTo("DEGRADED");
        assertThat(health.getDetails()).containsEntry("deadLetter", 1L);
    }

    @Test
    void reportsAlertableDegradedStatusWhenOldestPendingExceedsThreshold() {
        queue(1, 0, 0, NOW.minus(Duration.ofMinutes(30)));

        Health health = indicator.health();

        assertThat(health.getStatus().getCode()).isEqualTo("DEGRADED");
        assertThat(health.getDetails()).containsEntry("staleUndelivered", true);
    }

    @Test
    void reportsAlertableDegradedStatusWhenInFlightWorkIsStuck() {
        queue(0, 1, 0, NOW.minus(Duration.ofMinutes(31)));

        Health health = indicator.health();

        assertThat(health.getStatus().getCode()).isEqualTo("DEGRADED");
        assertThat(health.getDetails()).containsEntry("staleUndelivered", true);
    }

    private void queue(long pending, long inFlight, long deadLetter, Instant oldestPendingAt) {
        when(repository.countByStatus(CollabNotificationOutboxStatus.PENDING)).thenReturn(pending);
        when(repository.countByStatus(CollabNotificationOutboxStatus.IN_FLIGHT)).thenReturn(inFlight);
        when(repository.countByStatus(CollabNotificationOutboxStatus.DEAD_LETTER)).thenReturn(deadLetter);
        when(repository.findOldestCreatedAtByStatusIn(List.of(
                CollabNotificationOutboxStatus.PENDING,
                CollabNotificationOutboxStatus.IN_FLIGHT)))
                .thenReturn(Optional.ofNullable(oldestPendingAt));
    }
}
