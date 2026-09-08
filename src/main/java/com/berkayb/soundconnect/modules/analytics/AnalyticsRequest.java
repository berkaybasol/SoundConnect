package com.berkayb.soundconnect.modules.analytics;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@JsonDeserialize(using = AnalyticsRequestDeserializer.class)
public record AnalyticsRequest(UUID clientId, List<Observation> observations) {
    public enum Type { EVENT_IMPRESSION, EVENT_DETAIL_VIEW, VENUE_PROFILE_VIEW }
    public record Observation(UUID id, Type type, UUID eventId, UUID venueId, UUID sourceEventId, Instant observedAt) { }
}
