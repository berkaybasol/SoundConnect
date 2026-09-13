package com.berkayb.soundconnect.modules.feed.musician.api;

import java.time.Instant;
import java.util.UUID;

public record MusicianFeedMutedAuthorResponse(
        String profileType,
        UUID profileId,
        String displayName,
        String avatarUrl,
        boolean available,
        Instant mutedAt
) {
    public MusicianFeedMutedAuthorResponse {
        if (!available) {
            displayName = null;
            avatarUrl = null;
        }
    }
}
