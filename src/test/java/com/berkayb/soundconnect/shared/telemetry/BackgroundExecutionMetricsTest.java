package com.berkayb.soundconnect.shared.telemetry;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.berkayb.soundconnect.shared.telemetry.BackgroundExecutionMetrics.Schedule.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BackgroundExecutionMetricsTest {
    @Test void inactiveUnknownAndCompletedTicksAreDifferentAndOriginalFailureSurvives() {
        var registry = new SimpleMeterRegistry();
        try {
            var metrics = new BackgroundExecutionMetrics(registry);
            assertThat(registry.get("soundconnect.scheduler.last.started").tag("schedule", "follow_outbox").gauge().value()).isNaN();
            metrics.observe(follow_outbox, () -> assertThat(registry.get("soundconnect.scheduler.active").tag("schedule", "follow_outbox").gauge().value()).isEqualTo(1));
            var failure = new IllegalStateException("PRIVATE-token-email-path");
            assertThatThrownBy(() -> metrics.observe(follow_outbox, () -> { throw failure; })).isSameAs(failure);
            assertThat(registry.get("soundconnect.scheduler.active").tag("schedule", "follow_outbox").gauge().value()).isZero();
            assertThat(registry.get("soundconnect.scheduler.last.completed").tag("schedule", "follow_outbox").gauge().value()).isPositive();
            assertThat(registry.get("soundconnect.scheduler.duration").tags("schedule", "follow_outbox", "outcome", "completed").timer().count()).isEqualTo(1);
            assertThat(registry.get("soundconnect.scheduler.duration").tags("schedule", "follow_outbox", "outcome", "failed").timer().count()).isEqualTo(1);
            metrics.duplicateNotificationAttempt(); metrics.duplicateNotificationAttempt();
            assertThat(registry.get("soundconnect.notification.duplicate.attempts").counter().count()).isEqualTo(2);
            assertThat(registry.getMeters().toString()).doesNotContain("PRIVATE");
        } finally { registry.close(); }
    }

    @Test void brokenRegistryAndDisabledObserverNeverSkipOrRepeatProductAction() {
        var broken = mock(MeterRegistry.class);
        when(broken.timer(anyString(), any(String[].class))).thenThrow(new IllegalStateException("private"));
        when(broken.counter(anyString(), any(String[].class))).thenThrow(new IllegalStateException("private"));
        var calls = new AtomicInteger();
        var metrics = new BackgroundExecutionMetrics(broken);
        metrics.observe(campaigns, calls::incrementAndGet);
        metrics.duplicateNotificationAttempt();
        BackgroundExecutionMetrics.noop().observe(campaigns, calls::incrementAndGet);
        assertThat(calls).hasValue(2);
    }

    @Test void overlappingCallsHaveAccurateActiveCountAndAlwaysRelease() throws Exception {
        var registry = new SimpleMeterRegistry();
        var executor = Executors.newFixedThreadPool(2);
        var release = new CountDownLatch(1);
        try {
            var metrics = new BackgroundExecutionMetrics(registry);
            var entered = new CountDownLatch(2);
            Runnable work = () -> metrics.observe(follow_outbox, () -> {
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test deadline"); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
            });
            var first = executor.submit(work); var second = executor.submit(work);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(registry.get("soundconnect.scheduler.active").tag("schedule", "follow_outbox").gauge().value()).isEqualTo(2);
            release.countDown(); first.get(5, TimeUnit.SECONDS); second.get(5, TimeUnit.SECONDS);
            assertThat(registry.get("soundconnect.scheduler.active").tag("schedule", "follow_outbox").gauge().value()).isZero();
        } finally { release.countDown(); executor.shutdownNow(); registry.close(); }
    }
}
