package com.berkayb.soundconnect.modules.analytics;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class AnalyticsResponse {
    private AnalyticsResponse() { }
    public record Acknowledgement(List<UUID> acknowledgedIds) { }
    public record Metrics(long impressions, long detailViews, long profileVisits) {
        public static final Metrics ZERO = new Metrics(0, 0, 0);
    }
    public enum ComparisonStatus { AVAILABLE, NOT_STARTED, INSUFFICIENT_HISTORY, RETENTION_LIMIT }
    public enum EventSort { DATE, REACH, DETAIL_VIEWS, PROFILE_VISITS }
    public record DailyPoint(LocalDate date, Metrics metrics, boolean partial) { }
    public record PeriodComparison(ComparisonStatus status, LocalDate currentFromDate, LocalDate currentToDate,
                                   LocalDate previousFromDate, LocalDate previousToDate,
                                   Metrics currentMetrics, Metrics previousMetrics) { }
    public record Summary(UUID venueId, @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) UUID eventId, LocalDate fromDate, LocalDate toDate, int days,
                          String timeZone, Instant trackingStartedAt, Instant updatedAt, Metrics metrics,
                          List<DailyPoint> daily, PeriodComparison comparison) {
        public Summary(UUID venueId, UUID eventId, LocalDate fromDate, LocalDate toDate, int days,
                       String timeZone, Instant trackingStartedAt, Instant updatedAt, Metrics metrics) {
            this(venueId, eventId, fromDate, toDate, days, timeZone, trackingStartedAt, updatedAt, metrics, List.of(), null);
        }
    }
    public record EventItem(UUID eventId, String title, LocalDate eventDate, Metrics metrics) { }
    public record EventPage(List<EventItem> content, int page, int size, long totalElements, int totalPages, boolean hasNext,
                            EventSort sort) {
        public EventPage(List<EventItem> content, int page, int size, long totalElements, int totalPages, boolean hasNext) {
            this(content, page, size, totalElements, totalPages, hasNext, EventSort.DATE);
        }
    }
}
