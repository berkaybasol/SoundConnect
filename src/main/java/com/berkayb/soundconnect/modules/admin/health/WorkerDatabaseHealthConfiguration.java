package com.berkayb.soundconnect.modules.admin.health;

import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;

/** Explicit worker import: no API controller, full health graph or additional DataSource. */
@Configuration(proxyBeanMethods = false)
@Profile("media-worker")
@ConditionalOnNotWebApplication
@ConditionalOnProperty(prefix = "app.system-health", name = "postgres-sessions-enabled", havingValue = "true")
@EnableConfigurationProperties
@Import(SystemHealthProperties.class)
public class WorkerDatabaseHealthConfiguration {
    @Bean
    SystemHealthService workerDatabaseHealthService(DataSource dataSource, SystemHealthProperties properties,
                                                   Environment environment) {
        return SystemHealthService.workerDatabase(dataSource, properties, environment);
    }
}
