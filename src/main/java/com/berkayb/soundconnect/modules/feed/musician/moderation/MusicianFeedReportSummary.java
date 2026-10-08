package com.berkayb.soundconnect.modules.feed.musician.moderation;

import java.time.Instant;
import java.util.UUID;

public record MusicianFeedReportSummary(UUID id, long version, MusicianFeedReportStatus status,
        String itemId, String itemType, String targetType, UUID targetId, String reason,
        Instant reportedAt, String title, String authorDisplayName) { }
