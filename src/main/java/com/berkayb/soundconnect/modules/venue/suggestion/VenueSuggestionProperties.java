package com.berkayb.soundconnect.modules.venue.suggestion;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter @Setter @Validated
@ConfigurationProperties("app.venue-suggestions")
public class VenueSuggestionProperties {
    @Min(1) @Max(10) private int hourlyLimit = 5;
    @Min(1) @Max(30) private int dailyLimit = 15;
    @Min(1) @Max(1000) private int globalHourlyLimit = 200;
    @Min(1) @Max(20) private int dispatchBatchSize = 5;
}
