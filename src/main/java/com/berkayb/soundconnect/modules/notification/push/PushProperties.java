package com.berkayb.soundconnect.modules.notification.push;

import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;

@Getter
@Setter
@Validated
@ConfigurationProperties("app.notification.push")
public class PushProperties {
    private boolean enabled;
    // An external base64 AES-256 key, never checked into source control.
    private String tokenEncryptionKey = "";
    @NotNull private Set<NotificationType> allowedTypes = EnumSet.of(NotificationType.DM_NEW_MESSAGE);
    @Min(1) @Max(16) private int workerThreads = 2;
    @Min(1) @Max(100) private int batchSize = 25;
    @Min(1) @Max(25) private int maxAttempts = 8;
    @Min(1) @Max(50) private int maxDevicesPerUser = 10;
    @Min(50) @Max(1000) private int maxStoredDevicesPerUser = 100;
    @Min(10) @Max(600) private int maxDeviceMutationsPerMinute = 60;
    @Min(1) @Max(1000) private int maxSendsPerSecond = 20;
    @NotNull private Duration leaseDuration = Duration.ofMinutes(2);
    @NotNull private Duration messageTtl = Duration.ofHours(24);
    @NotNull private Duration retryInitialDelay = Duration.ofSeconds(30);
    @NotNull private Duration retryMaxDelay = Duration.ofMinutes(30);
    @NotNull private Duration terminalRetention = Duration.ofDays(7);
    @NotNull private Duration deviceStaleAfter = Duration.ofDays(60);
    @NotNull private Duration unhealthyAge = Duration.ofMinutes(5);

    @AssertTrue(message = "Push timing configuration is unsafe")
    public boolean isTimingSafe() {
        return positive(leaseDuration) && leaseDuration.compareTo(Duration.ofMinutes(2)) >= 0
                && leaseDuration.compareTo(Duration.ofMinutes(10)) <= 0
                && positive(messageTtl) && messageTtl.compareTo(Duration.ofDays(28)) <= 0
                && positive(retryInitialDelay) && positive(retryMaxDelay)
                && retryMaxDelay.compareTo(retryInitialDelay) >= 0
                && positive(terminalRetention) && positive(deviceStaleAfter) && positive(unhealthyAge);
    }
    private static boolean positive(Duration value) {
        return value != null && !value.isNegative() && !value.isZero();
    }
}
