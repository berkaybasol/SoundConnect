package com.berkayb.soundconnect.modules.collab.outbox;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "app.notification.collab-outbox")
public class CollabNotificationOutboxProperties {

    @Min(1)
    @Max(100)
    private int batchSize = 25;

    @Min(1)
    @Max(100)
    private int maxAttempts = 8;

    @Min(1)
    @Max(16)
    private int workerThreads = 2;

    @Min(1)
    @Max(10_000)
    private int queueCapacity = 250;

    @NotNull
    private Duration leaseDuration = Duration.ofSeconds(30);

    @NotNull
    private Duration retryInitialDelay = Duration.ofSeconds(5);

    @NotNull
    private Duration retryMaxDelay = Duration.ofMinutes(15);

    @NotNull
    private Duration publisherConfirmTimeout = Duration.ofSeconds(5);

    @NotNull
    private Duration publishedRetention = Duration.ofDays(7);

    @NotNull
    private Duration healthUndeliveredAgeThreshold = Duration.ofMinutes(30);

    @AssertTrue(message = "Collab notification outbox timing configuration is unsafe")
    public boolean isTimingConfigurationSafe() {
        if (!positive(leaseDuration)
                || !positive(retryInitialDelay)
                || !positive(retryMaxDelay)
                || !positive(publisherConfirmTimeout)
                || !positive(publishedRetention)
                || !positive(healthUndeliveredAgeThreshold)) {
            return false;
        }
        return !retryMaxDelay.minus(retryInitialDelay).isNegative()
                && leaseDuration.compareTo(publisherConfirmTimeout.plusSeconds(1)) >= 0
                && healthUndeliveredAgeThreshold.compareTo(retryMaxDelay) >= 0;
    }

    private static boolean positive(Duration value) {
        return value != null && !value.isZero() && !value.isNegative();
    }
}
