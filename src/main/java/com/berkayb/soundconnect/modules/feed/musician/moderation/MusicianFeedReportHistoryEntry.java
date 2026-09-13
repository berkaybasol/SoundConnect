package com.berkayb.soundconnect.modules.feed.musician.moderation;

import java.time.Instant;
import java.util.UUID;

public record MusicianFeedReportHistoryEntry(UUID id, MusicianFeedReportDecision decision,
        MusicianFeedReportStatus previousStatus, MusicianFeedReportStatus status,
        UUID actorUserId, Instant occurredAt, String resolutionNote) { }
