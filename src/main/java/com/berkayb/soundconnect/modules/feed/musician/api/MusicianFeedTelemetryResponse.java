package com.berkayb.soundconnect.modules.feed.musician.api;

import java.time.Instant;
import java.util.UUID;

public record MusicianFeedTelemetryResponse(
        UUID eventId,
        UUID clientEventId,
        MusicianFeedTelemetryEventType eventType,
        boolean duplicate,
        Instant recordedAt
) { }
