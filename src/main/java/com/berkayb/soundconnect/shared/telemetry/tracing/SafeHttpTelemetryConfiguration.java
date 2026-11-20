package com.berkayb.soundconnect.shared.telemetry.tracing;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(SafeHttpTelemetryProperties.class)
@Import(SafeTelemetryConfiguration.class)
public class SafeHttpTelemetryConfiguration {
    @Bean(destroyMethod = "")
    @ConditionalOnProperty(prefix = "app.observability.http-telemetry", name = "enabled", havingValue = "true")
    SafeHttpObservationHandler safeHttpObservationHandler(SafeTelemetryRuntime runtime) {
        return new SafeHttpObservationHandler(runtime, false);
    }
}
