package com.berkayb.soundconnect.modules.notification.dto.response;

import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import java.time.Instant;
import java.util.UUID;

/** No raw payload, participant list, personal snapshot, note or chat content. */
public record TableNotificationTargetResponse(
        UUID notificationId, UUID recipientId, NotificationType type, UUID tableGroupId,
        Kind kind, String event, Instant occurredAt, String description,
        String tableStatus, String participantStatus, UUID subjectId, UUID applicationId,
        boolean sameApplication, String reason, boolean read) {
    public enum Kind { PENDING_APPLICATION, CHAT, RESULT }
}
