package com.berkayb.soundconnect.shared.messaging.events.notification;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "app.messaging.notification")
public class NotificationPublisherProperties {

    @NotNull
    private Duration publisherConfirmTimeout = Duration.ofSeconds(5);

    @AssertTrue(message = "app.messaging.notification.publisher-confirm-timeout must be between 1s and 30s")
    public boolean isPublisherConfirmTimeoutValid() {
        return publisherConfirmTimeout != null
                && publisherConfirmTimeout.compareTo(Duration.ofSeconds(1)) >= 0
                && publisherConfirmTimeout.compareTo(Duration.ofSeconds(30)) <= 0;
    }
}
