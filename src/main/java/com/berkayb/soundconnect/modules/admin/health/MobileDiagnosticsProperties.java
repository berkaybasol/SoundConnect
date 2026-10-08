package com.berkayb.soundconnect.modules.admin.health;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component @ConfigurationProperties("app.diagnostics.mobile") @Validated @Getter @Setter
public class MobileDiagnosticsProperties {
    private boolean enabled = true;
    @Pattern(regexp = "local|staging|production") private String environment = "local";
    @Min(1) @Max(30) private int retentionDays = 7;
    @Min(100) @Max(1000000) private int maxEvents = 100000;
    @Min(1) @Max(100) private int perAccountPerMinute = 20;
    @Min(1) @Max(1000) private int perIpPerMinute = 60;
    @Min(1) @Max(10000) private int globalPerMinute = 200;
    @Min(1) @Max(1000) private int errorThreshold = 20;
}
