package com.berkayb.soundconnect.modules.event.plan;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
public record EventPlanVersionRequest(@JsonDeserialize(using = EventPlanJson.Revision.class) Long expectedVersion) { }
