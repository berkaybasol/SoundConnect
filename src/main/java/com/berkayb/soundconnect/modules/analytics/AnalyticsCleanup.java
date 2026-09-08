package com.berkayb.soundconnect.modules.analytics;

import com.berkayb.soundconnect.modules.event.support.EventScheduleClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor @Slf4j
public class AnalyticsCleanup {
    private final AnalyticsProperties properties;
    private final AnalyticsStore store;
    private final EventScheduleClock clock;
    @Scheduled(fixedDelayString = "${app.venue-analytics.cleanup-delay-ms:10000}", initialDelayString = "${app.venue-analytics.cleanup-delay-ms:60000}")
    public void cleanup() {
        try {
            // Turning collection off must not turn retention off. Default-disabled installations
            // without the additive schema are detected without querying nonexistent tables.
            if (!properties.isEnabled() && !store.schemaInstalled()) return;
            // Bounded catch-up: at most20 short transactions, each deleting at most1000 rows/table.
            for (int batch=0;batch<20;batch++) if (store.cleanup(clock.instant())<1000) break;
        }
        catch (RuntimeException failure) { log.warn("Venue analytics cleanup unavailable; exceptionType={}", failure.getClass().getSimpleName()); }
    }
}
