package com.berkayb.soundconnect.modules.notification.dlq;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter @Setter @Validated
@ConfigurationProperties("app.messaging.notification.dlq-ops")
public class NotificationDlqProperties {
    private boolean replayEnabled = false;
    @Min(1) @Max(25) private int window = 10;
    @Min(1024) @Max(262144) private int maxBodyBytes = 65536;
    @Min(1024) @Max(1048576) private int maxWindowBytes = 262144;
    @Min(100) @Max(5000) private int rpcTimeoutMs = 2000;
    @Min(1000) @Max(30000) private int deadlineMs = 10000;
    @Min(1000) @Max(300000) private int intervalMs = 30000;
    @Min(2000) @Max(600000) private int staleMs = 90000;
    @AssertTrue(message = "DLQ deadline must exceed RPC timeout; staleness must exceed interval")
    public boolean isTimingValid() { return deadlineMs > rpcTimeoutMs && staleMs > intervalMs; }
}
