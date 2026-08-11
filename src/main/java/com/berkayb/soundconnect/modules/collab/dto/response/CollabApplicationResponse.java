package com.berkayb.soundconnect.modules.collab.dto.response;

import com.berkayb.soundconnect.modules.collab.enums.CollabApplicationStatus;

import java.time.Instant;
import java.util.UUID;

public record CollabApplicationResponse(
        UUID id,
        long version,
        CollabApplicationStatus status,
        CollabListingResponse listing,
        CollabActorSummary applicant,
        String phoneNumber,
        String message,
        Instant submittedAt,
        Instant statusChangedAt,
        Instant decidedAt
) {}
