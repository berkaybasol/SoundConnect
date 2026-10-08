package com.berkayb.soundconnect.modules.admin.health;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@ConfigurationProperties("app.system-health")
@Validated @Getter @Setter
public class SystemHealthProperties {
    @Min(5) @Max(300) private int refreshIntervalSeconds = 15;
    @Min(15) @Max(1800) private int staleAfterSeconds = 60;
    @Min(100) @Max(5000) private int probeTimeoutMillis = 2000;
    @Min(1) @Max(3600) private int backlogAgeThresholdSeconds = 1800;
    @Min(100) @Max(30000) private int apiMeanLatencyThresholdMillis = 1000;
    @Min(1) @Max(100) private int apiErrorRateThresholdPercent = 5;
    @Min(1) @Max(1000) private int apiMinimumRequests = 20;
    @Min(1) @Max(1000000) private int queueReadyThreshold = 1000;
    /** Optional read-only mount of the existing media worker readiness marker. */
    private String mediaWorkerHealthFile = "";

    @AssertTrue(message = "Health staleness must exceed refresh interval")
    public boolean isTimingValid() { return staleAfterSeconds > refreshIntervalSeconds; }
}
