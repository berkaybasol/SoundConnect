package com.berkayb.soundconnect.modules.event.plan;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
public record EventPlanUpdateRequest(@JsonDeserialize(using = EventPlanJson.Revision.class) Long expectedVersion,
        EventPlanDefinition definition) { }
