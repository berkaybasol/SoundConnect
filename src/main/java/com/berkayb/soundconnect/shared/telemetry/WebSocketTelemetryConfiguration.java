package com.berkayb.soundconnect.shared.telemetry;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.socket.config.WebSocketMessageBrokerStats;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

/** Additional passive configurer; existing endpoints, broker and security interceptors remain authoritative. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "app.telemetry.metrics", name = "enabled", havingValue = "true")
public class WebSocketTelemetryConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "app.telemetry.metrics", name = "service-name", havingValue = "soundconnect-api")
    WebSocketMeterBinder webSocketMeterBinder(ObjectProvider<WebSocketMessageBrokerStats> stats) {
        return new WebSocketMeterBinder(stats);
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.telemetry.metrics", name = "service-name", havingValue = "soundconnect-api")
    WebSocketMessageBrokerConfigurer webSocketTelemetryConfigurer(WebSocketMeterBinder binder) {
        return new ObservationConfigurer(binder);
    }

    private record ObservationConfigurer(WebSocketMeterBinder binder) implements WebSocketMessageBrokerConfigurer, Ordered {
        @Override public int getOrder() { return Ordered.LOWEST_PRECEDENCE; }
        @Override public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
            registration.addDecoratorFactory(binder::decorate);
        }
    }
}
