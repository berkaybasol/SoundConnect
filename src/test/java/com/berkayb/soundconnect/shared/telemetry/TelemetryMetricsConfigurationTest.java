package com.berkayb.soundconnect.shared.telemetry;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.registry.otlp.OtlpMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.export.otlp.OtlpMetricsExportAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.export.simple.SimpleMetricsExportAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TelemetryMetricsConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MetricsAutoConfiguration.class, CompositeMeterRegistryAutoConfiguration.class,
                    SimpleMetricsExportAutoConfiguration.class, OtlpMetricsExportAutoConfiguration.class))
            .withUserConfiguration(TelemetryMetricsConfiguration.class)
            .withPropertyValues("management.otlp.metrics.export.enabled=false");

    @Test void disabledIsNetworkFreeAndKeepsExistingLocalRegistry() {
        context.run(result -> {
            assertThat(result).hasNotFailed().doesNotHaveBean(OtlpMeterRegistry.class).hasSingleBean(SimpleMeterRegistry.class);
            result.getBean(MeterRegistry.class).counter("existing.product.metric").increment();
            assertThat(result.getBean(MeterRegistry.class).get("existing.product.metric").counter().count()).isEqualTo(1);
        });
    }

    @Test void explicitEnabledExporterDoesNotRemoveLocalProductMetrics() {
        context.withPropertyValues("app.telemetry.metrics.enabled=true", "app.telemetry.metrics.endpoint=http://127.0.0.1:1/v1/metrics",
                "app.telemetry.metrics.service-name=soundconnect-api", "app.telemetry.metrics.environment=staging").run(result -> {
            assertThat(result).hasNotFailed().hasSingleBean(OtlpMeterRegistry.class).hasSingleBean(SimpleMeterRegistry.class)
                    .hasSingleBean(CompositeMeterRegistry.class);
            var exporter = result.getBean(OtlpMeterRegistry.class);
            exporter.stop();
            result.getBean(MeterRegistry.class).counter("existing.product.metric", "private", "SECRET").increment();
            assertThat(result.getBean(SimpleMeterRegistry.class).get("existing.product.metric").counter().count()).isEqualTo(1);
            assertThat(exporter.find("existing.product.metric").counter()).isNull();
        });
    }

    @Test void invalidEnabledTargetFailsClosedBeforePublishing() {
        context.withPropertyValues("app.telemetry.metrics.enabled=true", "app.telemetry.metrics.endpoint=http://PUBLIC:4318/v1/metrics",
                "app.telemetry.metrics.service-name=soundconnect-api", "app.telemetry.metrics.environment=staging").run(result -> {
            assertThat(result).hasFailed();
            assertThat(result.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class);
        });
    }

    @Test void workerRegistersOnlyItsDbHealthSubsetWithoutApiHealthGraph() {
        context.withPropertyValues("app.telemetry.metrics.enabled=true", "app.telemetry.metrics.endpoint=http://127.0.0.1:1/v1/metrics",
                "app.telemetry.metrics.service-name=soundconnect-media-worker", "app.telemetry.metrics.environment=staging").run(result -> {
            assertThat(result).hasNotFailed();
            var exporter = result.getBean(OtlpMeterRegistry.class);
            exporter.stop();
            var health = exporter.getMeters().stream().filter(meter -> meter.getId().getName().startsWith("soundconnect.health.")).toList();
            // Six statuses, observed and measurement age, eight role metrics, four database continuity metrics.
            assertThat(health).hasSize(20).allSatisfy(meter ->
                    assertThat(meter.getId().getTag("component")).isEqualTo("databaseSessions"));
            assertThat(exporter.get("soundconnect.health.observed").gauge().value()).isZero();
            for (String metric : List.of("sessions", "database.deadlocks", "database.stats.reset.known",
                    "database.stats.reset.epoch", "database.postmaster.start.epoch")) {
                assertThat(exporter.get("soundconnect.health.postgres." + metric).gauge().value()).as(metric).isNaN();
            }
        });
    }
}
