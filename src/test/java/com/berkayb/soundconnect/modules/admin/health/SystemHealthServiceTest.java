package com.berkayb.soundconnect.modules.admin.health;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.berkayb.soundconnect.modules.admin.health.SystemHealthProbe.Reading;
import static com.berkayb.soundconnect.modules.admin.health.SystemHealthSnapshot.*;
import static org.assertj.core.api.Assertions.assertThat;

class SystemHealthServiceTest {
    @Test void initialAndRepeatedHttpReadsNeverInvokeSources() {
        AtomicInteger calls = new AtomicInteger();
        try (var service = service(probe(() -> { calls.incrementAndGet(); return healthy(Instant.now()); }), new MutableClock())) {
            for (int i = 0; i < 100; i++) {
                var result = service.snapshot();
                assertThat(result.status()).isEqualTo(Status.UNKNOWN);
                assertThat(result.components().getFirst().measuredAt()).isNull();
                assertThat(result.components().getFirst().metrics()).isEmpty();
            }
            assertThat(calls).hasValue(0);
        }
    }

    @Test void failureDoesNotRenewMeasurementAndAgeTurnsStaleThenRecovers() {
        MutableClock clock = new MutableClock();
        AtomicBoolean failing = new AtomicBoolean();
        try (var service = service(probe(() -> {
            if (failing.get()) throw new IllegalStateException("sensitive-account-token");
            return healthy(clock.instant());
        }), clock)) {
            service.refresh();
            Instant first = service.snapshot().components().getFirst().measuredAt();
            assertThat(service.snapshot().status()).isEqualTo(Status.UP);
            failing.set(true); clock.advance(10); service.refresh();
            var failed = service.snapshot().components().getFirst();
            assertThat(failed.status()).isEqualTo(Status.UNKNOWN);
            assertThat(failed.measuredAt()).isEqualTo(first);
            assertThat(failed.reasonCode()).isEqualTo(ReasonCode.PROBE_FAILED);
            clock.advance(51);
            assertThat(service.snapshot().components().getFirst().status()).isEqualTo(Status.STALE);
            failing.set(false); service.refresh();
            assertThat(service.snapshot().status()).isEqualTo(Status.UP);
            assertThat(service.snapshot().components().getFirst().measuredAt()).isEqualTo(clock.instant());
        }
    }

    @Test void timedOutUninterruptibleSourceIsNeverDuplicatedAndItsLateResultIsDiscarded() throws Exception {
        CountDownLatch release = new CountDownLatch(1), returned = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        MutableClock clock = new MutableClock();
        try (var service = service(probe(() -> {
            calls.incrementAndGet();
            while (release.getCount() > 0) {
                try { release.await(); } catch (InterruptedException ignored) { /* Simulate a blocked driver. */ }
            }
            returned.countDown();
            return healthy(clock.instant());
        }), clock)) {
            service.refresh();
            assertThat(service.snapshot().components().getFirst().reasonCode()).isEqualTo(ReasonCode.PROBE_TIMEOUT);
            for (int i = 0; i < 20; i++) service.refresh();
            assertThat(calls).hasValue(1);
            release.countDown();
            assertThat(returned.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(service.snapshot().components().getFirst().measuredAt()).isNull();
            assertThat(service.snapshot().status()).isEqualTo(Status.UNKNOWN);
        } finally { release.countDown(); }
    }

    @Test void knownDependencyFailureAndDisabledFeaturesStayDistinctFromUnknown() {
        MutableClock clock = new MutableClock();
        try (var service = service(probe(() -> new Reading(Status.DOWN, ReasonCode.DEPENDENCY_UNAVAILABLE,
                clock.instant(), Map.of())), clock)) {
            service.refresh(); assertThat(service.snapshot().status()).isEqualTo(Status.DOWN);
        }
        assertThat(SystemHealthService.aggregate(List.of(Status.UP, Status.DISABLED))).isEqualTo(Status.UP);
        assertThat(SystemHealthService.aggregate(List.of(Status.DISABLED))).isEqualTo(Status.DISABLED);
        assertThat(SystemHealthService.aggregate(List.of(Status.UP, Status.UNKNOWN))).isEqualTo(Status.UNKNOWN);
        assertThat(SystemHealthService.aggregate(List.of(Status.UP, Status.STALE))).isEqualTo(Status.STALE);
        assertThat(SystemHealthService.aggregate(List.of(Status.DOWN, Status.UNKNOWN))).isEqualTo(Status.DOWN);
    }

    @Test void forwardDatedSourceCannotClaimHealthy() {
        MutableClock clock = new MutableClock();
        try (var service = service(probe(() -> healthy(clock.instant().plusSeconds(1))), clock)) {
            service.refresh();
            assertThat(service.snapshot().status()).isEqualTo(Status.UNKNOWN);
            assertThat(service.snapshot().components().getFirst().reasonCode()).isEqualTo(ReasonCode.INVALID_MEASUREMENT);
        }
    }

    private static SystemHealthProbe probe(java.util.function.Supplier<Reading> read) {
        return new SystemHealthProbe("database", "Veritabanı", "Veri erişimi", read);
    }
    private static Reading healthy(Instant at) { return new Reading(Status.UP, ReasonCode.HEALTHY, at, Map.of("pending", 0d)); }
    private static SystemHealthService service(SystemHealthProbe probe, Clock clock) {
        var properties = new SystemHealthProperties(); properties.setProbeTimeoutMillis(100);
        return new SystemHealthService(List.of(probe), properties, clock, false);
    }
    static class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-08T09:00:00Z");
        void advance(long seconds) { now = now.plusSeconds(seconds); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
