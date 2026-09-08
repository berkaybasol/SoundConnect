package com.berkayb.soundconnect.modules.analytics;

import com.berkayb.soundconnect.modules.event.support.EventScheduleClock;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/** Never exposes observation IDs, subjects, visitor hashes, targets, IPs, or secrets. */
@Component("venueAnalytics") @RequiredArgsConstructor
public class AnalyticsHealthIndicator implements HealthIndicator {
    private final AnalyticsProperties properties;
    private final AnalyticsStore store;
    private final EventScheduleClock clock;
    @Override public Health health() {
        if (!properties.isEnabled()) return Health.up().withDetail("collection","disabled").build();
        try {
            return store.retentionHealthy(clock.instant()) ? Health.up().build()
                    : Health.status("DEGRADED").withDetail("reason","retention_cleanup_delayed").build();
        } catch (RuntimeException unavailable) {
            return Health.down().withDetail("reason","schema_or_storage_unavailable").build();
        }
    }
}
