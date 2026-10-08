package com.berkayb.soundconnect.modules.message.dm.abuse;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter @Setter @Validated
@ConfigurationProperties(prefix = "app.dm.rate-limit")
public class DmRateLimitProperties {
    private boolean enabled = true;
    @Min(1) @Max(10000) private int globalLimit = 120;
    @Min(1) @Max(10000) private int recipientLimit = 60;
    @Min(1) @Max(3600) private int windowSeconds = 60;
}
