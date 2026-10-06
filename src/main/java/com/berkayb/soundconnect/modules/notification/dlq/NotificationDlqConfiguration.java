package com.berkayb.soundconnect.modules.notification.dlq;

import org.springframework.boot.actuate.health.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NotificationDlqProperties.class)
public class NotificationDlqConfiguration {
    @Bean HealthIndicator notificationDlqHealthIndicator(NotificationDlqOperations operations) {
        // No queue I/O or message delivery from health; public details stay disabled.
        return () -> {
            String status = operations.summary().status();
            return Health.status(status.equals("UNAVAILABLE") ? "UNKNOWN" : status).build();
        };
    }
}
