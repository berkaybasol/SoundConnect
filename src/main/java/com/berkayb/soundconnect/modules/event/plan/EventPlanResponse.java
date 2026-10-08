package com.berkayb.soundconnect.modules.event.plan;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
public record EventPlanResponse(UUID id, long version, EventPlanDefinition definition, String venueName,
        String performerName, String posterUrl, EventPlanStatus status, EventPlanConsentStatus consentStatus,
        boolean showOnProfile, LocalDate generatedThrough, Instant serverNow, boolean decisionAllowed,
        boolean withdrawAllowed) { }
