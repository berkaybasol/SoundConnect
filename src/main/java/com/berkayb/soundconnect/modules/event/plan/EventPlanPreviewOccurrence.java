package com.berkayb.soundconnect.modules.event.plan;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/** Scalar read projection: preview does not hydrate or lock events. A missing event preserves the tombstone. */
public record EventPlanPreviewOccurrence(LocalDate scheduledDate, LocalDate eventDate,
        EventPlanOccurrenceStatus status, UUID liveEventId, LocalTime startTime) { }
