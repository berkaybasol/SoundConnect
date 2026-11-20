package com.berkayb.soundconnect.shared.telemetry;

import io.micrometer.registry.otlp.AggregationTemporality;
import io.micrometer.registry.otlp.HistogramFlavor;
import io.micrometer.registry.otlp.OtlpConfig;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Override every OTLP environment-sensitive default, including headers and resource identity. */
final class ExplicitOtlpMetricsConfig implements OtlpConfig {
    private final String endpoint;
    private final Duration step;
    private final int batchSize;
    private final Map<String, String> resources;

    ExplicitOtlpMetricsConfig(TelemetryMetricsProperties properties) {
        properties.validateEnabled();
        endpoint = properties.getEndpoint();
        step = properties.getStep();
        batchSize = properties.getMaxSeries();
        resources = Map.of("service.name", properties.getServiceName(),
                "deployment.environment", properties.getEnvironment(),
                "application", properties.getServiceName().equals("soundconnect-api") ? "api" : "media-worker");
    }

    @Override public String get(String key) { return null; }
    @Override public boolean enabled() { return true; }
    @Override public String url() { return endpoint; }
    @Override public Duration step() { return step; }
    @Override public int batchSize() { return batchSize; }
    @Override public Map<String, String> headers() { return Map.of(); }
    @Override public Map<String, String> resourceAttributes() { return resources; }
    @Override public AggregationTemporality aggregationTemporality() { return AggregationTemporality.CUMULATIVE; }
    @Override public HistogramFlavor histogramFlavor() { return HistogramFlavor.EXPLICIT_BUCKET_HISTOGRAM; }
    @Override public int maxScale() { return 4; }
    @Override public int maxBucketCount() { return 32; }
    @Override public TimeUnit baseTimeUnit() { return TimeUnit.SECONDS; }
}
