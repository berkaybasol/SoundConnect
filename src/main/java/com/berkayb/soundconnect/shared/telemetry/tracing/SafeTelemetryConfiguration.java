package com.berkayb.soundconnect.shared.telemetry.tracing;

import com.berkayb.soundconnect.shared.telemetry.TelemetryMetricsProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/** Also explicitly imported by the non-web worker; neither signal opts in by default. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({SafeHttpTelemetryProperties.class, SafeDependencyTelemetryProperties.class,
        TelemetryMetricsProperties.class})
public class SafeTelemetryConfiguration {
    @Bean(destroyMethod = "close")
    @Conditional(AnySignalEnabled.class)
    SafeTelemetryRuntime safeTelemetryRuntime(SafeHttpTelemetryProperties properties,
                                               TelemetryMetricsProperties identity) {
        return SafeTelemetryRuntime.create(properties, identity.getServiceName());
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.observability.dependency-telemetry", name = "enabled", havingValue = "true")
    SafeDependencyTelemetry safeDependencyTelemetry(SafeTelemetryRuntime runtime, MeterRegistry registry) {
        return new SafeDependencyTelemetry(runtime, registry);
    }

    static final class AnySignalEnabled extends AnyNestedCondition {
        AnySignalEnabled() { super(ConfigurationPhase.REGISTER_BEAN); }
        @ConditionalOnProperty(prefix = "app.observability.http-telemetry", name = "enabled", havingValue = "true")
        static class Http { }
        @ConditionalOnProperty(prefix = "app.observability.dependency-telemetry", name = "enabled", havingValue = "true")
        static class Dependency { }
    }
}
