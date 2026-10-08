package com.berkayb.soundconnect.modules.admin.health;

import com.berkayb.soundconnect.shared.realtime.WebSocketBrokerRelayHealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.time.Clock;
import java.util.Set;

/** Opt-in local acceptance only: all selected measurements use real production sources.
 * Other production dependencies are absent from this fixture, not represented as healthy.
 * No production source selection or behavior is changed. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "ready01.monitor.hold.enabled", havingValue = "true")
@Import({SystemHealthController.class, SystemHealthSources.class, SystemHealthProperties.class,
        RabbitQueueHealthSources.class, StorageHealthObservation.class, WebSocketBrokerRelayHealthIndicator.class})
public class AuthMonitorFixtureConfiguration {
    public static final Set<String> PROBE_IDS = Set.of("database", "redis", "rabbit", "realtime");

    @Bean WebServerFactoryCustomizer<TomcatServletWebServerFactory> restartableFixtureConnector() {
        return factory -> factory.addConnectorCustomizers(connector -> connector.setProperty("bindOnInit", "false"));
    }

    @Bean SystemHealthService healthService(SystemHealthSources sources, SystemHealthProperties properties) {
        // The regular Gradle test profile disables the daemon. Only this explicitly opted-in
        // fixture starts the real bounded scheduler/cache for its four owned dependencies.
        var selected = sources.probes().stream().filter(probe -> PROBE_IDS.contains(probe.id())).toList();
        if (selected.size() != PROBE_IDS.size()) throw new IllegalStateException("Fixture probe selection mismatch");
        return new SystemHealthService(selected, properties, Clock.systemUTC(), true);
    }
}
