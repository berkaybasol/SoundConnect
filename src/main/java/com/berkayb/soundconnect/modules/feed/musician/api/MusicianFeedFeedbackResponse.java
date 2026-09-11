package com.berkayb.soundconnect.modules.feed.musician.api;

import java.time.Instant;
import java.util.UUID;

public record MusicianFeedFeedbackResponse(
        UUID id,
        MusicianFeedFeedbackAction action,
        String itemId,
        String authorProfileType,
        UUID authorProfileId,
        Instant recordedAt
) { }
