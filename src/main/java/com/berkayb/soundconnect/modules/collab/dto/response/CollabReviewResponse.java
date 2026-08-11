package com.berkayb.soundconnect.modules.collab.dto.response;

import java.time.Instant;
import java.util.UUID;

public record CollabReviewResponse(
        UUID id,
        UUID jobId,
        CollabActorSummary reviewer,
        CollabActorSummary target,
        int rating,
        String comment,
        Instant submittedAt
) {}
