package com.berkayb.soundconnect.modules.event.audience;

import com.berkayb.soundconnect.modules.event.dto.response.EventResponseDto;
import java.time.Instant;
import java.util.UUID;

public final class EventIntentResponse {
    private EventIntentResponse() { }
    public record State(UUID eventId, EventIntent intent, boolean publishedOnProfile, String note, long version,
                        Instant updatedAt, boolean eventAvailable, boolean eventEnded, boolean canSetIntent,
                        boolean canPublish, boolean publicationVisible, EventResponseDto event, UUID postId,
                        Long likeCount, Long commentCount, Boolean likedByMe) {
        public State(UUID eventId, EventIntent intent, boolean publishedOnProfile, String note, long version,
                     Instant updatedAt, boolean eventAvailable, boolean eventEnded, boolean canSetIntent,
                     boolean canPublish, boolean publicationVisible, EventResponseDto event, UUID postId) {
            this(eventId, intent, publishedOnProfile, note, version, updatedAt, eventAvailable, eventEnded,
                    canSetIntent, canPublish, publicationVisible, event, postId, null, null, null);
        }
        public State withEngagement(long likes, long comments, boolean liked) {
            return new State(eventId, intent, publishedOnProfile, note, version, updatedAt, eventAvailable,
                    eventEnded, canSetIntent, canPublish, publicationVisible, event, postId, likes, comments, liked);
        }
    }
    public record Post(UUID eventId, EventIntent intent, String note, Instant publishedAt, boolean eventEnded,
                       EventResponseDto event, UUID postId, Long likeCount, Long commentCount,
                       Boolean likedByMe, State viewerIntentState) {
        public Post(UUID eventId, EventIntent intent, String note, Instant publishedAt, boolean eventEnded,
                    EventResponseDto event, UUID postId) {
            this(eventId, intent, note, publishedAt, eventEnded, event, postId, null, null, null, null);
        }
    }
}
