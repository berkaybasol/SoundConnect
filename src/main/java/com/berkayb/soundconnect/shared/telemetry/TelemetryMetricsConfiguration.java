package com.berkayb.soundconnect.shared.telemetry;

import com.berkayb.soundconnect.modules.admin.health.SystemHealthMeterBinder;
import com.berkayb.soundconnect.modules.admin.health.SystemHealthService;
import com.berkayb.soundconnect.modules.admin.health.PostgresHealthMetrics;

import java.util.Set;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.registry.otlp.OtlpMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** No web endpoints, credentials, platform auto-detection or external-service probes. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TelemetryMetricsProperties.class)
@ConditionalOnProperty(prefix = "app.telemetry.metrics", name = "enabled", havingValue = "true")
public class TelemetryMetricsConfiguration {
    @Bean
    @ConditionalOnMissingBean(SimpleMeterRegistry.class)
    SimpleMeterRegistry localTelemetrySourceRegistry() {
        // Boot otherwise removes its fallback simple registry when an exporter is added.
        // The composite retains all existing product metrics even when export rejects a series.
        return new SimpleMeterRegistry();
    }

    @Bean
    OtlpMeterRegistry telemetryOtlpMeterRegistry(TelemetryMetricsProperties properties) {
        properties.validateEnabled();
        var registry = new BoundedOtlpMeterRegistry(new ExplicitOtlpMetricsConfig(properties));
        var filter = new TelemetryExportMeterFilter(properties.getMaxSeries());
        registry.config().meterFilter(filter);
        Gauge.builder("soundconnect.telemetry.export.series.budget", filter, TelemetryExportMeterFilter::maxWireSeries).strongReference(true).register(registry);
        Gauge.builder("soundconnect.telemetry.export.series.used", filter, TelemetryExportMeterFilter::usedWireSeries).strongReference(true).register(registry);
        Gauge.builder("soundconnect.telemetry.export.meters.denied", filter, TelemetryExportMeterFilter::deniedMeters).strongReference(true).register(registry);
        Gauge.builder("soundconnect.telemetry.export.failures", registry, BoundedOtlpMeterRegistry::failedPublications).register(registry);
        Gauge.builder("soundconnect.telemetry.export.unsupported", registry, BoundedOtlpMeterRegistry::unsupportedMeters).register(registry);
        Gauge.builder("soundconnect.telemetry.export.last.success", registry, BoundedOtlpMeterRegistry::lastSuccessEpochSeconds).baseUnit("seconds").register(registry);
        // Cumulative export has one publisher, no retry/batch queue, one bounded series batch.
        // Our sender uses 1 s connect / 3 s request deadlines and never follows redirects,
        // uses a system proxy or retains receiver bodies. No cloud secret lives in this process.
        return registry;
    }

    @Bean
    MeterBinder cachedSystemHealthMeterBinder(ObjectProvider<SystemHealthService> services, TelemetryMetricsProperties properties) {
        // Defer lookup until collection: health sources themselves depend on MeterRegistry.
        return new SystemHealthMeterBinder(() -> {
            SystemHealthService service = services.getIfAvailable();
            return service == null ? null : service.snapshot();
        }, properties.getServiceName().equals("soundconnect-api") ? TelemetryExportMeterFilter.COMPONENTS
                : Set.of(PostgresHealthMetrics.SESSIONS));
    }
}
