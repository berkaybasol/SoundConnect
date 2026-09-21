package com.berkayb.soundconnect.modules.event.plan;
import java.util.UUID;
public record EventPlanCreateRequest(UUID clientRequestId, EventPlanDefinition definition) { }
