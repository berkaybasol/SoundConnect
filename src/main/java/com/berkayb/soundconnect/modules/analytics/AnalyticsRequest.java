package com.berkayb.soundconnect.modules.analytics;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@JsonDeserialize(using = AnalyticsRequestDeserializer.class)
public record AnalyticsRequest(UUID clientId, List<Observation> observations) {
    public enum Type {
        EVENT_IMPRESSION, EVENT_DETAIL_VIEW, VENUE_PROFILE_VIEW,
        ANNOUNCEMENT_IMPRESSION, ANNOUNCEMENT_DETAIL_VIEW,
        ANNOUNCEMENT_VIDEO_START, ANNOUNCEMENT_VIDEO_COMPLETE;

        public boolean announcement() { return name().startsWith("ANNOUNCEMENT_"); }
        public boolean video() { return this == ANNOUNCEMENT_VIDEO_START || this == ANNOUNCEMENT_VIDEO_COMPLETE; }
    }
    public enum Source { FEED, DIRECTORY }
    public record Observation(UUID id, Type type, UUID eventId, UUID venueId, UUID sourceEventId, Instant observedAt,
                              UUID announcementId, Source source, UUID playbackId, String impressionToken) {
        public Observation(UUID id, Type type, UUID eventId, UUID venueId, UUID sourceEventId, Instant observedAt) {
            this(id, type, eventId, venueId, sourceEventId, observedAt, null, null, null, null);
        }
    }
}
