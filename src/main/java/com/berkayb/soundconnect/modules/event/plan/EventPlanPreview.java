package com.berkayb.soundconnect.modules.event.plan;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
public record EventPlanPreview(List<LocalDate> dates, LocalDate throughDate, boolean hasMore, Instant serverNow,
        List<EventPlanPreservedDate> preservedDates) {
    public EventPlanPreview(List<LocalDate> dates, LocalDate throughDate, boolean hasMore, Instant serverNow) {
        this(dates, throughDate, hasMore, serverNow, List.of());
    }
}
