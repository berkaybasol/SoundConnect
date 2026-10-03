package com.berkayb.soundconnect.modules.studio.reservation.outbox;

import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationInboundEvent;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public record StudioReservationNotificationOutboxClaim(
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
    public StudioReservationNotificationOutboxClaim {
        payload = Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }

    @Override public String toString() {
        return "StudioReservationNotificationOutboxClaim[eventId=" + eventId + ", type=" + type
                + ", attemptCount=" + attemptCount + "]";
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
