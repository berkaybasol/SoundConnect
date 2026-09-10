package com.berkayb.soundconnect.modules.overthinking.outbox;

import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationInboundEvent;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

final class OverthinkingOutboxTestEvents {
    private OverthinkingOutboxTestEvents() { }

    static Map<String, Object> receivedPayload() {
        return Map.of("module", "OVERTHINKING", "action", "REVEAL_REQUEST_RECEIVED",
                "postId", UUID.randomUUID().toString(), "postTitle", "Geceye bir not",
                "revealRequestId", UUID.randomUUID().toString(), "requesterId", UUID.randomUUID().toString());
    }

    static NotificationInboundEvent received() {
        return NotificationInboundEvent.builder()
                .eventId(UUID.randomUUID()).recipientId(UUID.randomUUID())
                .type(NotificationType.OVERTHINKING_REVEAL_REQUEST_RECEIVED)
                .title("Profil görüntüleme isteği").message("Birisi bu yazıda profilini görüntülemek istiyor.")
                .payload(receivedPayload()).emailForce(false).occurredAt(Instant.parse("2026-09-09T12:00:00Z"))
                .build();
    }
}
