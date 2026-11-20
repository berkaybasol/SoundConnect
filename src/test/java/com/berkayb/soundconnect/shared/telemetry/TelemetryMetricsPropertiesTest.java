package com.berkayb.soundconnect.shared.telemetry;

import io.micrometer.registry.otlp.AggregationTemporality;
import io.micrometer.registry.otlp.HistogramFlavor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class TelemetryMetricsPropertiesTest {
    static TelemetryMetricsProperties enabled(String endpoint) {
        var properties = new TelemetryMetricsProperties();
        properties.setEnabled(true);
        properties.setEndpoint(endpoint);
        properties.setServiceName("soundconnect-api");
        properties.setEnvironment("staging");
        return properties;
    }

    @Test void disabledDoesNotRequireAnyEndpointOrIdentity() {
        var properties = new TelemetryMetricsProperties();
        assertThat(properties.isEnabled()).isFalse();
        assertThatCode(properties::validateEnabled).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://172.30.241.1:4318/v1/metrics", "http://127.0.0.1:4318/v1/metrics",
            "https://10.20.0.1:4318/v1/metrics", "http://192.168.1.1:4318/v1/metrics"})
    void acceptsOnlyExplicitPrivateLiteralCollectors(String endpoint) {
        assertThatCode(enabled(endpoint)::validateEnabled).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "http://localhost:4318/v1/metrics", "http://alloy:4318/v1/metrics",
            "http://169.254.169.254:4318/v1/metrics", "http://8.8.8.8:4318/v1/metrics",
            "http://127.0.0.1/v1/metrics", "http://127.0.0.1:4318/anything", "http://127.0.0.1:4318/v1/metrics?token=SECRET",
            "http://user:SECRET@127.0.0.1:4318/v1/metrics", "http://127.0.0.1:4318/v1/metrics#SECRET",
            "file:///v1/metrics", "http://[::1]:4318/v1/metrics", "http://2130706433:4318/v1/metrics",
            "http://127.00.0.1:4318/v1/metrics", "http://127.0.0.1:4318/v1/%6detrics",
            "http://127.0.0.1:0/v1/metrics", "http://172.32.0.1:4318/v1/metrics"})
    void rejectsDnsMetadataPublicEncodedCredentialsAndAmbiguousTargetsWithoutEchoingInput(String endpoint) {
        assertThatThrownBy(enabled(endpoint)::validateEnabled).isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("SECRET");
    }

    @Test void explicitIdentityStepAndBudgetAreBoundedAndNoOtelDefaultsCanLeak() {
        var properties = enabled("http://172.30.241.1:4318/v1/metrics");
        var config = new ExplicitOtlpMetricsConfig(properties);
        assertThat(config.resourceAttributes()).isEqualTo(Map.of("service.name", "soundconnect-api",
                "deployment.environment", "staging", "application", "api"));
        assertThat(config.headers()).isEmpty();
        assertThat(config.url()).isEqualTo(properties.getEndpoint());
        assertThat(config.step()).isEqualTo(Duration.ofSeconds(30));
        assertThat(config.batchSize()).isEqualTo(1024);
        assertThat(config.aggregationTemporality()).isEqualTo(AggregationTemporality.CUMULATIVE);
        assertThat(config.histogramFlavor()).isEqualTo(HistogramFlavor.EXPLICIT_BUCKET_HISTOGRAM);
        properties.setServiceName("secret-account-id");
        assertThatThrownBy(properties::validateEnabled).hasMessageNotContaining("secret-account-id");
        properties.setServiceName("soundconnect-media-worker");
        properties.setStep(Duration.ofSeconds(1));
        assertThatThrownBy(properties::validateEnabled).isInstanceOf(IllegalArgumentException.class);
        properties.setStep(Duration.ofSeconds(30));
        properties.setMaxSeries(100000);
        assertThatThrownBy(properties::validateEnabled).isInstanceOf(IllegalArgumentException.class);
    }
}
