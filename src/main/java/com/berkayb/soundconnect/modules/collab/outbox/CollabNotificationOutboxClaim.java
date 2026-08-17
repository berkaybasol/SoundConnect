package com.berkayb.soundconnect.modules.collab.outbox;

import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationInboundEvent;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public record CollabNotificationOutboxClaim(
        UUID eventId,
        UUID recipientId,
        NotificationType type,
        String title,
        String message,
        Map<String, Object> payload,
        boolean emailForce,
        Instant occurredAt,
        int attemptCount,
        String leaseOwner
) {
    public CollabNotificationOutboxClaim {
        payload = Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }

    public NotificationInboundEvent toInboundEvent() {
        return NotificationInboundEvent.builder()
                .eventId(eventId)
                .recipientId(recipientId)
                .type(type)
                .title(title)
                .message(message)
                .payload(payload)
                .emailForce(emailForce)
                .occurredAt(occurredAt)
                .build();
    }
}
