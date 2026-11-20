package com.berkayb.soundconnect.modules.venue.suggestion;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("venueSuggestionOutboxHealth") @RequiredArgsConstructor
public class VenueSuggestionHealthIndicator implements HealthIndicator {
    private final VenueSuggestionStore store;
    @Override public Health health() {
        try {
            var counts = store.healthCounts();
            long review = ((Number) counts.get("review")).longValue();
            long stale = ((Number) counts.get("stale")).longValue();
            var health = Health.status(review > 0 || stale > 0 ? "DEGRADED" : "UP")
                    .withDetail("review", review).withDetail("stale", stale)
                    .withDetail("pending", ((Number) counts.get("pending")).longValue());
            if (counts.get("oldestPendingAt") instanceof java.time.Instant oldest)
                health.withDetail("oldestPendingAt", oldest);
            return health.build();
        } catch (RuntimeException unavailable) {
            return Health.unknown().withDetail("reason", "storage_or_migration_unavailable").build();
        }
    }
}
