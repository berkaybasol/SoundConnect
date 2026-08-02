package com.berkayb.soundconnect.modules.backline.catalog.support;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

@Component
public class BacklineCatalogTimeProvider {
    private final Clock clock;

    public BacklineCatalogTimeProvider() {
        this(Clock.systemUTC());
    }

    BacklineCatalogTimeProvider(Clock clock) {
        this.clock = clock;
    }

    public Instant now() {
        return clock.instant();
    }
}
