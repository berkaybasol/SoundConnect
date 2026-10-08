package com.berkayb.soundconnect.modules.event.plan;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

public record EventPlanDefinition(UUID venueId, LocalDate startDate, LocalDate untilDate,
        @JsonDeserialize(contentUsing = EventPlanJson.Weekday.class) List<Integer> weekdays,
        List<LocalDate> excludedDates, EventPlanTemplate template) { }
