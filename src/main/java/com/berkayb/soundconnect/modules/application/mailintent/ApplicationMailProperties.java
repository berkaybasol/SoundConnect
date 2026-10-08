package com.berkayb.soundconnect.modules.application.mailintent;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter @Setter @Validated
@ConfigurationProperties("app.application-mail")
public class ApplicationMailProperties {
    @Min(1) @Max(20) private int batchSize = 5;
    @Min(1) @Max(20) private int maxAttempts = 10;
    @Min(10) @Max(600) private int leaseSeconds = 120;
    @Min(1) @Max(3600) private int retryBaseSeconds = 10;
    @Min(1) @Max(86400) private int retryMaxSeconds = 600;
    @Min(100) @Max(60000) private long pollDelayMs = 1000;
    @Min(0) @Max(3600000) private long initialDelayMs = 15000;
}
