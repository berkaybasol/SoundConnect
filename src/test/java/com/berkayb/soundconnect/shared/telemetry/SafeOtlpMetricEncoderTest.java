package com.berkayb.soundconnect.shared.telemetry;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.FunctionTimer;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.metrics.v1.Metric;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class SafeOtlpMetricEncoderTest {
    @Test void encodesCumulativeFunctionMetersAndPreservesMissingGaugeAsExplicitNan() throws Exception {
        var registry = new SimpleMeterRegistry();
        try {
            registry.config().meterFilter(new TelemetryExportMeterFilter(1024));
            var value = new AtomicLong(5);
            FunctionCounter.builder("jvm.classes.unloaded", value, AtomicLong::doubleValue).register(registry);
            FunctionTimer.builder("hikaricp.connections.acquire", value, AtomicLong::longValue, v -> 25d, TimeUnit.MILLISECONDS).register(registry);
            Gauge.builder("soundconnect.health.pending", () -> Double.NaN).tag("component", "push").register(registry);
            Gauge.builder("soundconnect.health.observed", () -> 0d).tag("component", "push").register(registry);
            var encoder = new SafeOtlpMetricEncoder(Map.of("service.name", "soundconnect-api"), 1_000_000);
            var payload = ExportMetricsServiceRequest.parseFrom(encoder.encode(registry.getMeters(), 2_000_000));
            var metrics = payload.getResourceMetrics(0).getScopeMetrics(0).getMetricsList();
            Metric missing = metrics.stream().filter(metric -> metric.getName().equals("soundconnect.health.pending")).findFirst().orElseThrow();
            assertThat(missing.getGauge().getDataPoints(0).getAsDouble()).isNaN();
            Metric counter = metrics.stream().filter(metric -> metric.getName().equals("jvm.classes.unloaded")).findFirst().orElseThrow();
            assertThat(counter.getSum().getIsMonotonic()).isTrue();
            assertThat(counter.getSum().getDataPoints(0).getAsDouble()).isEqualTo(5);
            Metric timer = metrics.stream().filter(metric -> metric.getName().equals("hikaricp.connections.acquire")).findFirst().orElseThrow();
            assertThat(timer.getHistogram().getDataPoints(0).getCount()).isEqualTo(5);
            assertThat(timer.getHistogram().getDataPoints(0).getSum()).isEqualTo(.025);
            assertThat(timer.getHistogram().getDataPoints(0).getBucketCountsList()).containsExactly(5L);
            assertThat(encoder.unsupportedMeters()).isZero();
        } finally {
            registry.close();
        }
    }

    @Test void unsupportedTypesAndUnsafeLateTagsFailClosedWithVisibleDenialCount() throws Exception {
        var registry = new SimpleMeterRegistry();
        try {
            registry.summary("jvm.memory.used").record(4);
            registry.counter("jvm.classes.unloaded", "private-user", "SECRET").increment();
            var encoder = new SafeOtlpMetricEncoder(Map.of("service.name", "soundconnect-api"), 1);
            var payload = ExportMetricsServiceRequest.parseFrom(encoder.encode(registry.getMeters(), 2));
            assertThat(payload.toString()).doesNotContain("SECRET", "private-user");
            assertThat(payload.getResourceMetrics(0).getScopeMetrics(0).getMetricsList()).isEmpty();
            assertThat(encoder.unsupportedMeters()).isEqualTo(2);
        } finally {
            registry.close();
        }
    }
}
