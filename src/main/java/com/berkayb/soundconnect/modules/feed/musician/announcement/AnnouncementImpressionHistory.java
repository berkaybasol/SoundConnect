package com.berkayb.soundconnect.modules.feed.musician.announcement;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Qualified account impressions; fetched only when a new feed session selects its fixed plan. */
public interface AnnouncementImpressionHistory {
    Map<UUID, Long> qualifiedImpressionCounts(UUID viewer, List<UUID> ids, Instant recordedBefore);
}
