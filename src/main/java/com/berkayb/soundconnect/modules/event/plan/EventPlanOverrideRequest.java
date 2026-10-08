package com.berkayb.soundconnect.modules.event.plan;
import java.time.LocalDate;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
public record EventPlanOverrideRequest(@JsonDeserialize(using = EventPlanJson.Revision.class) Long expectedVersion,
        LocalDate eventDate, EventPlanTemplate template) { }
