package com.berkayb.soundconnect.modules.event.plan;

import java.time.LocalDate;

public record EventPlanPreservedDate(LocalDate scheduledDate, LocalDate eventDate,
        EventPlanPreservationStatus status) { }
