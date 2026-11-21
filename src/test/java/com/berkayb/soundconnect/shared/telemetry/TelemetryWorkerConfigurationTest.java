package com.berkayb.soundconnect.shared.telemetry;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TelemetryWorkerConfigurationTest {
    @Test void isolatedWorkerYamlResolvesActualComposeEnvironmentWithoutBaseApplicationYaml() throws IOException {
        var environment = isolatedWorkerEnvironment(Map.of(
                "SOUNDCONNECT_TELEMETRY_METRICS_ENABLED", "true",
                "SOUNDCONNECT_TELEMETRY_METRICS_ENDPOINT", "http://172.30.241.1:4318/v1/metrics",
                "SOUNDCONNECT_TELEMETRY_SERVICE_NAME", "soundconnect-media-worker",
                "SOUNDCONNECT_TELEMETRY_ENVIRONMENT", "staging",
                "SOUNDCONNECT_TELEMETRY_METRICS_STEP", "45s",
                "SOUNDCONNECT_TELEMETRY_METRICS_MAX_SERIES", "768",
                "SOUNDCONNECT_DEPENDENCY_TELEMETRY_ENABLED", "true",
                "SOUNDCONNECT_HTTP_TELEMETRY_SAMPLE_PROBABILITY", "0.2",
                "SOUNDCONNECT_HTTP_TELEMETRY_COLLECTOR_ENDPOINT", "http://172.30.241.1:4318"));

        var properties = Binder.get(environment).bind("app.telemetry.metrics", TelemetryMetricsProperties.class).get();
        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getEndpoint()).isEqualTo("http://172.30.241.1:4318/v1/metrics");
        assertThat(properties.getServiceName()).isEqualTo("soundconnect-media-worker");
        assertThat(properties.getEnvironment()).isEqualTo("staging");
        assertThat(properties.getStep()).isEqualTo(Duration.ofSeconds(45));
        assertThat(properties.getMaxSeries()).isEqualTo(768);
        properties.validateEnabled();
        assertThat(environment.getProperty("app.observability.dependency-telemetry.enabled", Boolean.class)).isTrue();
        assertThat(environment.getProperty("app.observability.http-telemetry.enabled", Boolean.class)).isFalse();
        assertThat(environment.getProperty("app.observability.http-telemetry.sample-probability", Double.class)).isEqualTo(.2);
        assertThat(environment.getProperty("app.observability.http-telemetry.collector-endpoint")).isEqualTo("http://172.30.241.1:4318");
        assertClosedWorkerSurfaces(environment);
    }

    @Test void isolatedWorkerYamlRemainsDisabledByDefaultAndDoesNotInheritApiTelemetry() throws IOException {
        var environment = isolatedWorkerEnvironment(Map.of());
        var properties = Binder.get(environment).bind("app.telemetry.metrics", TelemetryMetricsProperties.class).get();

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.getEndpoint()).isEmpty();
        assertThat(properties.getServiceName()).isEmpty();
        assertThat(properties.getEnvironment()).isEmpty();
        assertThat(properties.getStep()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.getMaxSeries()).isEqualTo(1024);
        assertThat(environment.getProperty("app.observability.http-telemetry.enabled", Boolean.class)).isFalse();
        assertThat(environment.getProperty("app.observability.dependency-telemetry.enabled", Boolean.class)).isFalse();
        assertClosedWorkerSurfaces(environment);
    }

    private static MockEnvironment isolatedWorkerEnvironment(Map<String, Object> composeVariables) throws IOException {
        var environment = new MockEnvironment();
        // The dedicated worker uses spring.config.location, so application.yml is deliberately absent.
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("compose", composeVariables));
        new YamlPropertySourceLoader().load("worker", new ClassPathResource("application-media-worker.yml"))
                .forEach(source -> environment.getPropertySources().addLast(source));
        return environment;
    }

    private static void assertClosedWorkerSurfaces(MockEnvironment environment) {
        assertThat(environment.getProperty("spring.main.web-application-type")).isEqualTo("none");
        assertThat(environment.getProperty("management.otlp.metrics.export.enabled", Boolean.class)).isFalse();
        assertThat(environment.getProperty("management.endpoints.enabled-by-default", Boolean.class)).isFalse();
        assertThat(environment.getProperty("management.endpoints.web.exposure.exclude")).isEqualTo("*");
        assertThat(environment.getProperty("management.endpoints.jmx.exposure.exclude")).isEqualTo("*");
        assertThat(environment.getProperty("management.endpoint.health.enabled", Boolean.class)).isFalse();
    }
}
