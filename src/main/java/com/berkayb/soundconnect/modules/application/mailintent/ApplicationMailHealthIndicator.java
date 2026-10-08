package com.berkayb.soundconnect.modules.application.mailintent;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.*;
import org.springframework.stereotype.Component;

@Component("applicationMailIntentHealth") @RequiredArgsConstructor
public class ApplicationMailHealthIndicator implements HealthIndicator {
    private final ApplicationMailIntentStore store;
    @Override public Health health() {
        try {
            var counts=store.healthCounts();
            boolean degraded=((Number)counts.get("review")).longValue()>0 || ((Number)counts.get("stale")).longValue()>0;
            return (degraded ? Health.down() : Health.up()).withDetails(counts).build();
        } catch (RuntimeException unavailable) {
            return Health.down().withDetail("reason","storage_or_migration_unavailable").build();
        }
    }
}
