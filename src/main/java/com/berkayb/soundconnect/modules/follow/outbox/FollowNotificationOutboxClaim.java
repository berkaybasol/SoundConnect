package com.berkayb.soundconnect.modules.follow.outbox;

import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import java.time.Instant;
import java.util.UUID;

/** No name, avatar or other identity snapshot is retained in the durable intent. */
public record FollowNotificationOutboxClaim(UUID eventId, UUID occurrenceId, UUID followerId,
        UUID recipientId, UUID bandId, NotificationType type, Instant occurredAt,
        int attemptCount, String leaseOwner) { }
