package com.berkayb.soundconnect.shared.telemetry;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.metrics.v1.Metric;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class TelemetryOtlpWireTest {
    @Test void everyOperationalFamilyReachesActualOtlpWireWithItsFiniteDimensionsAndCorrectBuckets() throws Exception {
        try (var server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setResponseCode(200));
            server.enqueue(new MockResponse().setResponseCode(200));
            var properties = TelemetryMetricsPropertiesTest.enabled("http://127.0.0.1:" + server.getPort() + "/v1/metrics");
            var registry = new BoundedOtlpMeterRegistry(new ExplicitOtlpMetricsConfig(properties));
            try {
                registry.stop();
                var filter = new TelemetryExportMeterFilter(1024);
                registry.config().meterFilter(filter);
                var worker = new com.berkayb.soundconnect.modules.media.telemetry.MediaWorkerMetrics(registry);
                worker.startJob(com.berkayb.soundconnect.modules.media.telemetry.MediaWorkerMetrics.Job.HLS)
                        .finish(com.berkayb.soundconnect.modules.media.telemetry.MediaWorkerMetrics.JobOutcome.SUCCESS);
                registry.timer("soundconnect.worker.job.duration", "workflow", "hls", "outcome", "success").record(61, TimeUnit.SECONDS);
                var background = new BackgroundExecutionMetrics(registry);
                background.observe(BackgroundExecutionMetrics.Schedule.follow_outbox, () -> { });
                background.duplicateNotificationAttempt();
                registry.timer("soundconnect.auth.ratelimit.duration", "dimension", "account", "decision", "unavailable").record(20, TimeUnit.MILLISECONDS);
                registry.counter("soundconnect.auth.ratelimit.decisions", "dimension", "account", "decision", "unavailable").increment();
                registry.timer("soundconnect.dependency.duration", "provider", "s3", "operation", "head_object", "result", "success").record(2, TimeUnit.SECONDS);
                registry.timer("soundconnect.dependency.duration", "provider", "fcm", "operation", "head_object", "result", "success").record(1, TimeUnit.SECONDS);
                Gauge.builder("soundconnect.health.redis.used.memory", () -> Double.NaN).tag("component", "redis").register(registry);
                Gauge.builder("soundconnect.websocket.observed", () -> 1d).register(registry);
                Gauge.builder("soundconnect.websocket.sessions.active", () -> 2d).register(registry);
                var opened = new java.util.concurrent.atomic.AtomicLong(3);
                for (String name : List.of("soundconnect.websocket.sessions.opened", "soundconnect.websocket.sessions.closed", "soundconnect.websocket.transport.errors"))
                    io.micrometer.core.instrument.FunctionCounter.builder(name, opened, java.util.concurrent.atomic.AtomicLong::doubleValue).register(registry);
                ReflectionTestUtils.invokeMethod(registry, "publish");
                var request = server.takeRequest(5, TimeUnit.SECONDS);
                assertThat(request).isNotNull();
                var payload = ExportMetricsServiceRequest.parseFrom(request.getBody().readByteArray());
                var metrics = payload.getResourceMetrics(0).getScopeMetrics(0).getMetricsList();
                assertThat(metrics.stream().map(Metric::getName)).contains("soundconnect.worker.job.duration", "soundconnect.worker.ffmpeg.cpu.time",
                        "soundconnect.scheduler.duration", "soundconnect.notification.duplicate.attempts", "soundconnect.auth.ratelimit.duration",
                        "soundconnect.auth.ratelimit.decisions", "soundconnect.dependency.duration", "soundconnect.health.redis.used.memory",
                        "soundconnect.websocket.observed", "soundconnect.websocket.sessions.active", "soundconnect.websocket.sessions.opened",
                        "soundconnect.websocket.sessions.closed", "soundconnect.websocket.transport.errors");
                assertThat(metrics.stream().filter(metric -> metric.getName().equals("soundconnect.websocket.sessions.active"))
                        .findFirst().orElseThrow().getGauge().getDataPoints(0).getAsDouble()).isEqualTo(2);
                assertThat(metrics.stream().filter(metric -> metric.getName().equals("soundconnect.websocket.sessions.closed"))
                        .findFirst().orElseThrow().getSum().getDataPoints(0).getAsDouble()).isEqualTo(3);
                var hls = metrics.stream().filter(metric -> metric.getName().equals("soundconnect.worker.job.duration"))
                        .flatMap(metric -> metric.getHistogram().getDataPointsList().stream())
                        .filter(point -> point.getCount() == 2).findFirst().orElseThrow();
                assertThat(hls.getExplicitBoundsList()).containsExactly(1d, 5d, 10d, 30d, 60d, 120d, 300d, 600d, 1800d, 3600d);
                assertThat(hls.getBucketCountsList()).containsExactly(1L, 0L, 0L, 0L, 0L, 1L, 0L, 0L, 0L, 0L, 0L);
                assertThat(metrics.stream().filter(metric -> metric.getName().equals("soundconnect.dependency.duration"))
                        .findFirst().orElseThrow().getHistogram().getDataPointsCount()).isEqualTo(1);
                assertThat(metrics.stream().filter(metric -> metric.getName().equals("soundconnect.health.redis.used.memory"))
                        .findFirst().orElseThrow().getGauge().getDataPoints(0).getAsDouble()).isNaN();
                assertThat(registry.unsupportedMeters()).isZero();
                assertThat(metrics.stream().mapToInt(TelemetryOtlpWireTest::prometheusSeries).sum()).isLessThanOrEqualTo(filter.usedWireSeries());
            } finally { registry.close(); }
        }
    }

    @Test void realProtobufWireHasOnlySafeResourcesAttributesAndBoundedHistogramSeries() throws Exception {
        try (var server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setResponseCode(200));
            server.enqueue(new MockResponse().setResponseCode(200));
            var properties = TelemetryMetricsPropertiesTest.enabled("http://127.0.0.1:" + server.getPort() + "/v1/metrics");
            var registry = new BoundedOtlpMeterRegistry(new ExplicitOtlpMetricsConfig(properties));
            try {
                registry.stop();
                var filter = new TelemetryExportMeterFilter(256);
                registry.config().meterFilter(filter);
                // A later property filter must not expand the final wire histogram.
                registry.config().meterFilter(new MeterFilter() {
                    @Override public DistributionStatisticConfig configure(io.micrometer.core.instrument.Meter.Id id, DistributionStatisticConfig config) {
                        return DistributionStatisticConfig.builder().percentilesHistogram(true).percentiles(.5, .95, .99)
                                .serviceLevelObjectives(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15).build().merge(config);
                    }
                });
                Gauge.builder("jvm.memory.used", () -> 100).tags("area", "heap", "id", "SECRET-pool", "private-user", "SECRET")
                        .description("SECRET description").register(registry);
                Gauge.builder("soundconnect.health.pending", () -> Double.NaN).tag("component", "push").register(registry);
                for (String group : List.of("auth", "admin", "media", "profiles", "notifications", "venues", "events", "studios"))
                    for (String method : List.of("GET", "POST", "PUT", "DELETE"))
                        Timer.builder("http.server.requests").tags("uri", "/api/v1/" + group + "/SECRET?token=SECRET",
                                "method", method, "status", "200", "exception", "SECRET-exception")
                                .register(registry).record(100, TimeUnit.MILLISECONDS);
                for (String group : List.of("auth", "admin", "media", "profiles", "notifications", "venues", "events", "studios"))
                    for (String method : List.of("GET", "POST", "PUT", "DELETE"))
                        registry.timer("http.server.requests", "uri", "/api/v1/" + group + "/{id}", "method", method, "status", "500")
                                .record(250, TimeUnit.MILLISECONDS);
                registry.counter("SECRET-metric", "token", "SECRET").increment();
                ReflectionTestUtils.invokeMethod(registry, "publish");
                var request = server.takeRequest(5, TimeUnit.SECONDS);
                assertThat(request).isNotNull();
                assertThat(request.getPath()).isEqualTo("/v1/metrics");
                assertThat(request.getHeader("Authorization")).isNull();
                assertThat(request.getHeader("Content-Type")).isEqualTo("application/x-protobuf");
                var payload = ExportMetricsServiceRequest.parseFrom(request.getBody().readByteArray());
                assertThat(payload.toString()).doesNotContain("SECRET", "private-user", "exception", "Authorization", "host.name", "process.command");
                var resources = payload.getResourceMetrics(0).getResource().getAttributesList();
                assertThat(resources.stream().map(value -> value.getKey()).toList()).contains("service.name", "deployment.environment", "application")
                        .allMatch(key -> List.of("service.name", "deployment.environment", "application", "telemetry.sdk.name", "telemetry.sdk.language", "telemetry.sdk.version").contains(key));
                List<Metric> metrics = new ArrayList<>();
                payload.getResourceMetricsList().forEach(resource -> resource.getScopeMetricsList().forEach(scope -> metrics.addAll(scope.getMetricsList())));
                assertThat(metrics.stream().map(Metric::getName).toList()).contains("http.server.requests", "jvm.memory.used")
                        .doesNotContain("http_server_requests", "http.server.requests.max");
                assertThat(metrics.stream().filter(metric -> metric.getName().equals("soundconnect.health.pending"))
                        .findFirst().orElseThrow().getGauge().getDataPoints(0).getAsDouble()).isNaN();
                assertThat(metrics.stream().filter(Metric::hasHistogram).flatMap(metric -> metric.getHistogram().getDataPointsList().stream()).toList())
                        .isNotEmpty().allSatisfy(point -> {
                            assertThat(point.getExplicitBoundsCount()).isEqualTo(10);
                            assertThat(point.getBucketCountsCount()).isEqualTo(11);
                            assertThat(point.getBucketCountsList().stream().mapToLong(Long::longValue).sum()).isEqualTo(point.getCount());
                        });
                int wireSeries = metrics.stream().mapToInt(TelemetryOtlpWireTest::prometheusSeries).sum();
                assertThat(wireSeries).isLessThanOrEqualTo(filter.usedWireSeries()).isLessThanOrEqualTo(256);
                assertThat(filter.deniedMeters()).isGreaterThan(0);
            } finally {
                registry.close();
            }
        }
    }

    @Test void actualCumulativeOtlpTimerPreservesExclusiveBinsAcrossTwoExports() throws Exception {
        var properties = TelemetryMetricsPropertiesTest.enabled("http://127.0.0.1:1/v1/metrics");
        var registry = new BoundedOtlpMeterRegistry(new ExplicitOtlpMetricsConfig(properties));
        try {
            registry.stop();
            registry.config().meterFilter(new TelemetryExportMeterFilter(1024));
            var timer = registry.timer("http.server.requests", "uri", "/api/v1/media", "method", "POST", "status", "200");
            timer.record(1, TimeUnit.MILLISECONDS);
            timer.record(100, TimeUnit.MILLISECONDS);
            timer.record(100, TimeUnit.MILLISECONDS);
            timer.record(60, TimeUnit.SECONDS);
            var encoder = new SafeOtlpMetricEncoder(java.util.Map.of("service.name", "soundconnect-api"), 1);
            var first = ExportMetricsServiceRequest.parseFrom(encoder.encode(registry.getMeters(), 2))
                    .getResourceMetrics(0).getScopeMetrics(0).getMetrics(0).getHistogram().getDataPoints(0);
            assertThat(first.getCount()).isEqualTo(4);
            assertThat(first.getBucketCountsList()).containsExactly(1L, 0L, 0L, 2L, 0L, 0L, 0L, 0L, 0L, 0L, 1L);
            assertThat(first.getSum()).isEqualTo(60.201);
            timer.record(10, TimeUnit.MILLISECONDS);
            var second = ExportMetricsServiceRequest.parseFrom(encoder.encode(registry.getMeters(), 3))
                    .getResourceMetrics(0).getScopeMetrics(0).getMetrics(0).getHistogram().getDataPoints(0);
            assertThat(second.getCount()).isEqualTo(5);
            assertThat(second.getBucketCountsList()).containsExactly(1L, 1L, 0L, 2L, 0L, 0L, 0L, 0L, 0L, 0L, 1L);
            assertThat(encoder.unsupportedMeters()).isZero();
        } finally {
            registry.close();
        }
    }

    private static int prometheusSeries(Metric metric) {
        if (metric.hasGauge()) return metric.getGauge().getDataPointsCount();
        if (metric.hasSum()) return metric.getSum().getDataPointsCount();
        if (metric.hasHistogram()) return metric.getHistogram().getDataPointsList().stream().mapToInt(point -> point.getExplicitBoundsCount() + 3).sum();
        if (metric.hasSummary()) return metric.getSummary().getDataPointsList().stream().mapToInt(point -> point.getQuantileValuesCount() + 2).sum();
        throw new AssertionError("Unexpected unbudgeted OTLP metric type");
    }
}
