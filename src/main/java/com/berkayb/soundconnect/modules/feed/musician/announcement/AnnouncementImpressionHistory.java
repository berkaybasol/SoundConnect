package com.berkayb.soundconnect.modules.feed.musician.announcement;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Qualified account impressions; fetched only when a new feed session selects its fixed plan. */
public interface AnnouncementImpressionHistory {
    Duration REPEAT_COOLDOWN = Duration.ofHours(6);
    Duration FREQUENCY_WINDOW = Duration.ofHours(24);
    long MAX_RECENT_IMPRESSIONS = 2;

    Map<UUID, Long> qualifiedImpressionCounts(UUID viewer, List<UUID> ids, Instant recordedBefore);

    Map<UUID, QualifiedImpressions> qualifiedImpressionHistory(UUID viewer, List<UUID> ids, Instant recordedBefore);

    record QualifiedImpressions(long totalCount, long last24HoursCount, Instant lastRecordedAt) {
        public static final QualifiedImpressions NONE = new QualifiedImpressions(0, 0, null);

        public QualifiedImpressions {
            if (totalCount < 0 || last24HoursCount < 0 || last24HoursCount > totalCount
                    || ((totalCount == 0) != (lastRecordedAt == null))) {
                throw new IllegalArgumentException("Invalid qualified announcement impression history");
            }
        }

        /** Admission when a new fixed plan is selected, rather than a lock/reservation across sessions. */
        public boolean eligibleAt(Instant anchor) {
            return last24HoursCount < MAX_RECENT_IMPRESSIONS
                    && (lastRecordedAt == null || !lastRecordedAt.plus(REPEAT_COOLDOWN).isAfter(anchor));
        }
    }
}
