package com.berkayb.soundconnect.modules.feed.musician.cursor;

import java.time.Instant;
import java.util.UUID;

public record MusicianFeedCursorState(
        UUID viewerUserId,
        UUID feedSessionId,
        Instant anchor,
        CursorPosition after,
        long deliveredOrganicCount,
        long deliveredItemCount,
        String rankingContextVersion
) {
    public MusicianFeedCursorState(UUID viewerUserId, UUID feedSessionId, Instant anchor,
                                   CursorPosition after, long deliveredOrganicCount) {
        this(viewerUserId, feedSessionId, anchor, after, deliveredOrganicCount,
                deliveredOrganicCount, "0");
    }

    public record CursorPosition(long rankKey, Instant occurredAt, String itemId) { }
}
