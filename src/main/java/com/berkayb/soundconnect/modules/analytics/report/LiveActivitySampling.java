package com.berkayb.soundconnect.modules.analytics.report;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Component
@ConditionalOnProperty(name = "app.analytics-report.live-enabled", havingValue = "true")
@RequiredArgsConstructor
public class LiveActivitySampling {
    private final ReportHistory history;

    @Scheduled(cron = "15 * * * * *", zone = "Europe/Istanbul")
    public void sample() {
        history.captureLive(Instant.now().truncatedTo(ChronoUnit.MINUTES));
    }
}
