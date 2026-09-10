package com.berkayb.soundconnect.modules.notification.config;

import com.berkayb.soundconnect.modules.collab.outbox.CollabNotificationOutboxProperties;
import com.berkayb.soundconnect.modules.event.performer.outbox.EventPerformerNotificationOutboxProperties;
import com.berkayb.soundconnect.modules.overthinking.outbox.OverthinkingNotificationOutboxProperties;
import com.berkayb.soundconnect.modules.tablegroup.notification.outbox.TableGroupNotificationOutboxProperties;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationPublisherProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@RequiredArgsConstructor
public class NotificationOutboxLeaseConfigurationValidator {

    private static final Duration LEASE_SAFETY_MARGIN = Duration.ofSeconds(1);

    private final NotificationPublisherProperties publisherProperties;
    private final CollabNotificationOutboxProperties collabOutboxProperties;
    private final TableGroupNotificationOutboxProperties tableGroupOutboxProperties;
    private final EventPerformerNotificationOutboxProperties eventPerformerOutboxProperties;
    private final OverthinkingNotificationOutboxProperties overthinkingOutboxProperties;

    @PostConstruct
    void validateConfiguration() {
        if (!publisherProperties.isPublisherConfirmTimeoutValid()) {
            throw new IllegalStateException(
                    "app.messaging.notification.publisher-confirm-timeout must be between 1s and 30s"
            );
        }

        Duration minimumLease = publisherProperties.getPublisherConfirmTimeout().plus(LEASE_SAFETY_MARGIN);
        validateLease(
                "app.notification.collab-outbox.lease-duration",
                collabOutboxProperties.getLeaseDuration(),
                minimumLease
        );
        validateLease(
                "app.notification.table-group-outbox.lease-duration",
                tableGroupOutboxProperties.getLeaseDuration(),
                minimumLease
        );
        validateLease(
                "app.notification.event-performer-outbox.lease-duration",
                eventPerformerOutboxProperties.getLeaseDuration(),
                minimumLease
        );
        validateLease(
                "app.notification.overthinking-outbox.lease-duration",
                overthinkingOutboxProperties.getLeaseDuration(),
                minimumLease
        );
    }

    private static void validateLease(String key, Duration leaseDuration, Duration minimumLease) {
        if (leaseDuration == null || leaseDuration.compareTo(minimumLease) < 0) {
            throw new IllegalStateException(
                    key + " must be at least app.messaging.notification.publisher-confirm-timeout + 1s"
            );
        }
    }
}
