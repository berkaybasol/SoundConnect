package com.berkayb.soundconnect.modules.analytics;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Announcement reporting extends the shared analytics transport and permission boundaries. */
public final class AnnouncementAnalyticsResponse {
    private AnnouncementAnalyticsResponse() { }
    public record Metrics(long impressions, long uniqueReach, long detailViews, long videoStarts,
                          long videoCompletions, long likes, long comments, long hiders) {
        public static final Metrics ZERO = new Metrics(0, 0, 0, 0, 0, 0, 0, 0);
    }
    public record DailyPoint(LocalDate date, Metrics metrics) { }
    public record Summary(UUID announcementId, LocalDate fromDate, LocalDate toDate, String timeZone,
                          Instant updatedAt, Instant trackingStartedAt, Metrics metrics, List<DailyPoint> daily) { }
}
