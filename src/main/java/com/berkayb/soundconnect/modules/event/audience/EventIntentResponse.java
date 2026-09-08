package com.berkayb.soundconnect.modules.event.audience;

import com.berkayb.soundconnect.modules.event.dto.response.EventResponseDto;
import java.time.Instant;
import java.util.UUID;

public final class EventIntentResponse {
    private EventIntentResponse() { }
    public record State(UUID eventId, EventIntent intent, boolean publishedOnProfile, String note, long version,
                        Instant updatedAt, boolean eventAvailable, boolean eventEnded, boolean canSetIntent,
                        boolean canPublish, boolean publicationVisible, EventResponseDto event) { }
    public record Post(UUID eventId, EventIntent intent, String note, Instant publishedAt, boolean eventEnded, EventResponseDto event) { }
}
