package com.berkayb.soundconnect.modules.collab.dto.response;

import com.berkayb.soundconnect.modules.collab.enums.CollabJobStatus;

import java.time.Instant;
import java.util.UUID;

public record CollabJobResponse(
        UUID id,
        long version,
        CollabJobStatus status,
        CollabListingResponse listing,
        CollabActorSummary publisher,
        CollabActorSummary applicant,
        boolean publisherConfirmed,
        boolean applicantConfirmed,
        Instant publisherConfirmedAt,
        Instant applicantConfirmedAt,
        boolean confirmedByMe,
        boolean reviewedByMe,
        Instant completedAt
) {}
