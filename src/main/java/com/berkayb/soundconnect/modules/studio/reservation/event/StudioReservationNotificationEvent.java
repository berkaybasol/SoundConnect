package com.berkayb.soundconnect.modules.studio.reservation.event;

import com.berkayb.soundconnect.modules.notification.enums.NotificationType;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Immutable reservation notification snapshot published inside the domain
 * transaction and delivered only after that transaction commits.
 */
public record StudioReservationNotificationEvent(
        UUID recipientId,
        NotificationType type,
        String title,
        String message,
        Map<String, Object> payload,
        Instant occurredAt
) {
}
