package com.berkayb.soundconnect.modules.feed.musician.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record MusicianFeedPageResponse(
        int schemaVersion,
        String algorithmVersion,
        UUID feedSessionId,
        Instant generatedAt,
        List<MusicianFeedItemResponse> items,
        String nextCursor,
        boolean hasMore
) {
    public MusicianFeedPageResponse {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
