package com.berkayb.soundconnect.modules.event.performer.dto;

import java.util.UUID;

/** IDs required to lock an event-performer request in aggregate order. */
public record EventPerformerRequestLockTarget(UUID requestId, UUID eventId) {
}
