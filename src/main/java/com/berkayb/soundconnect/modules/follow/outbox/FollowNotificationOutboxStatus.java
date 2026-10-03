package com.berkayb.soundconnect.modules.follow.outbox;

public enum FollowNotificationOutboxStatus {
    PENDING,
    IN_FLIGHT,
    PUBLISHED,
    SUPPRESSED,
    DEAD_LETTER
}
