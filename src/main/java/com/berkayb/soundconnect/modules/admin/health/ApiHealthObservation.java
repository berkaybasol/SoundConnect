package com.berkayb.soundconnect.modules.admin.health;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.berkayb.soundconnect.modules.admin.health.SystemHealthProbe.Reading;
import static com.berkayb.soundconnect.modules.admin.health.SystemHealthSnapshot.*;

/** Window deltas avoid treating a historic incident as a permanent current alarm. */
final class ApiHealthObservation {
    private final MeterRegistry registry;
    private final SystemHealthProperties properties;
    private final Clock clock;
    private Sample previous;
    private record Sample(long requests, long errors, double elapsedMillis, Instant at) { }

    ApiHealthObservation(MeterRegistry registry, SystemHealthProperties properties, Clock clock) {
        this.registry = registry; this.properties = properties; this.clock = clock;
    }

    synchronized Reading read() {
        long requests = 0, errors = 0;
        double millis = 0;
        int series = 0;
        for (Timer timer : registry.find("http.server.requests").timers()) {
            if (++series > 512) return Reading.unknown(ReasonCode.METRIC_LIMIT_EXCEEDED);
            String uri = timer.getId().getTag("uri");
            if (uri != null && (uri.startsWith("/actuator") || uri.startsWith("/api/v1/admin/system-health"))) continue;
            long count = timer.count();
            requests += count;
            millis += timer.totalTime(TimeUnit.MILLISECONDS);
            String status = timer.getId().getTag("status");
            if (status != null && status.matches("5[0-9]{2}")) errors += count;
        }
        Instant now = clock.instant();
        Sample current = new Sample(requests, errors, millis, now), before = previous;
        previous = current;
        if (before == null || requests < before.requests || errors < before.errors || millis < before.elapsedMillis)
            return new Reading(Status.UNKNOWN, ReasonCode.WARMING_UP, now, Map.of());
        double window = Duration.between(before.at, now).toMillis() / 1000d;
        if (window <= 0 || !Double.isFinite(millis)) return Reading.unknown(ReasonCode.INVALID_MEASUREMENT);
        long count = requests - before.requests, failures = errors - before.errors;
        if (count == 0) return new Reading(Status.UNKNOWN, ReasonCode.NO_TRAFFIC, now,
                Map.of("requestCount", 0d, "windowSeconds", window));
        double mean = (millis - before.elapsedMillis) / count, rate = failures * 100d / count;
        boolean degraded = count >= properties.getApiMinimumRequests()
                && (mean >= properties.getApiMeanLatencyThresholdMillis() || rate >= properties.getApiErrorRateThresholdPercent());
        return new Reading(degraded ? Status.DEGRADED : Status.UP,
                degraded ? ReasonCode.DEGRADED_SIGNAL : ReasonCode.HEALTHY, now,
                Map.of("requestCount", (double) count, "serverErrorCount", (double) failures,
                        "serverErrorRatePercent", rate, "meanLatencyMillis", mean, "windowSeconds", window));
    }
}
