package com.berkayb.soundconnect.modules.admin.health;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static com.berkayb.soundconnect.modules.admin.health.SystemHealthSnapshot.*;
import static org.assertj.core.api.Assertions.assertThat;

class ApiHealthObservationTest {
    @Test void noTrafficIsUnknownAndMonitoringRequestsCannotMakeItHealthy() {
        var clock = new SystemHealthServiceTest.MutableClock();
        var registry = new SimpleMeterRegistry();
        try {
            var source = new ApiHealthObservation(registry, new SystemHealthProperties(), clock);
            assertThat(source.read().reasonCode()).isEqualTo(ReasonCode.WARMING_UP);
            registry.timer("http.server.requests", "uri", "/api/v1/admin/system-health", "status", "200").record(1, TimeUnit.MILLISECONDS);
            registry.timer("http.server.requests", "uri", "/actuator/health", "status", "200").record(1, TimeUnit.MILLISECONDS);
            clock.advance(15);
            var result = source.read();
            assertThat(result.status()).isEqualTo(Status.UNKNOWN);
            assertThat(result.reasonCode()).isEqualTo(ReasonCode.NO_TRAFFIC);
            assertThat(result.metrics()).containsEntry("requestCount", 0d).doesNotContainKey("meanLatencyMillis");
        } finally { registry.close(); }
    }

    @Test void intervalErrorsDegradeAndCleanNextIntervalRecovers() {
        var clock = new SystemHealthServiceTest.MutableClock();
        var registry = new SimpleMeterRegistry();
        try {
            var source = new ApiHealthObservation(registry, new SystemHealthProperties(), clock);
            source.read();
            for (int i = 0; i < 19; i++) registry.timer("http.server.requests", "uri", "/api/v1/items", "status", "200").record(30, TimeUnit.MILLISECONDS);
            registry.timer("http.server.requests", "uri", "/api/v1/items", "status", "500").record(30, TimeUnit.MILLISECONDS);
            clock.advance(15);
            var degraded = source.read();
            assertThat(degraded.status()).isEqualTo(Status.DEGRADED);
            assertThat(degraded.metrics()).containsEntry("serverErrorRatePercent", 5d).containsEntry("requestCount", 20d);
            for (int i = 0; i < 20; i++) registry.timer("http.server.requests", "uri", "/api/v1/items", "status", "200").record(30, TimeUnit.MILLISECONDS);
            clock.advance(15);
            assertThat(source.read().status()).isEqualTo(Status.UP);
        } finally { registry.close(); }
    }

    @Test void slowEnoughObservedIntervalDegradesAndMetricTagsNeverEscape() {
        var clock = new SystemHealthServiceTest.MutableClock();
        var registry = new SimpleMeterRegistry();
        try {
            var source = new ApiHealthObservation(registry, new SystemHealthProperties(), clock);
            source.read();
            for (int i = 0; i < 20; i++) registry.timer("http.server.requests", "uri", "/private-user-id", "status", "200").record(2, TimeUnit.SECONDS);
            clock.advance(15);
            var result = source.read();
            assertThat(result.status()).isEqualTo(Status.DEGRADED);
            assertThat(result.metrics()).containsEntry("meanLatencyMillis", 2000d);
            assertThat(result.toString()).doesNotContain("private-user-id");
        } finally { registry.close(); }
    }
}
