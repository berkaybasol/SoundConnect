package com.berkayb.soundconnect.modules.analytics;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter @Setter @Validated
@ConfigurationProperties("app.venue-analytics")
public class AnalyticsProperties {
    private boolean enabled = false;
    private boolean reportingEnabled = false;
    private String hmacSecret = "";
    @Min(20) @Max(10000) private int actorObservationsPerMinute = 600;
    @Min(1) @Max(10000) private int ipRequestsPerMinute = 300;
    @Min(1) @Max(100000) private int globalRequestsPerMinute = 3000;
    @Min(1) @Max(1000) private int ownerReadsPerMinute = 60;
}
