package com.berkayb.soundconnect.modules.feed.musician.api;

import java.util.List;

public record MusicianFeedMutedAuthorsResponse(
        List<MusicianFeedMutedAuthorResponse> items,
        String nextCursor,
        boolean hasMore
) {
    public MusicianFeedMutedAuthorsResponse {
        items = List.copyOf(items);
    }
}
