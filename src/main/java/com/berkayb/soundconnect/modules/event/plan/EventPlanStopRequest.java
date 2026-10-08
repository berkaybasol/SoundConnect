package com.berkayb.soundconnect.modules.event.plan;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.berkayb.soundconnect.modules.event.performer.dto.EventPerformerAcceptRequestDto.StrictBooleanDeserializer;
public record EventPlanStopRequest(@JsonDeserialize(using = EventPlanJson.Revision.class) Long expectedVersion,
        @JsonDeserialize(using = StrictBooleanDeserializer.class) Boolean cancelFuture) { }
