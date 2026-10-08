package com.berkayb.soundconnect.modules.notification.push.transport;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Getter
@Setter
@Validated
@ConfigurationProperties("app.notification.push.fcm")
public class FcmPushProperties {
    @NotBlank
    @Pattern(regexp = "[a-z][a-z0-9-]{4,28}[a-z0-9]")
    private String projectId;
    /** External service-account JSON only. Empty uses Application Default Credentials. */
    private String credentialsPath;
    private Duration connectTimeout = Duration.ofSeconds(3);
    private Duration readTimeout = Duration.ofSeconds(5);
    private Duration totalTimeout = Duration.ofSeconds(8);
    @Min(1)
    @Max(16)
    private int maxConcurrentRequests = 2;

    @AssertTrue(message = "FCM timeouts must be between 100ms and 10s, with connect/read <= total")
    public boolean isTimeoutConfigurationValid() {
        return bounded(connectTimeout) && bounded(readTimeout) && bounded(totalTimeout)
                && connectTimeout.compareTo(totalTimeout) <= 0
                && readTimeout.compareTo(totalTimeout) <= 0;
    }

    private boolean bounded(Duration duration) {
        return duration != null && duration.compareTo(Duration.ofMillis(100)) >= 0
                && duration.compareTo(Duration.ofSeconds(10)) <= 0;
    }
}
