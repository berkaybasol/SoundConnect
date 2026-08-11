package com.berkayb.soundconnect.modules.collab.dto.response;

import com.berkayb.soundconnect.modules.collab.enums.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CollabListingResponse(
        UUID id,
        long version,
        CollabListingStatus status,
        CollabClosureReason closureReason,
        CollabCadence cadence,
        CollabWantedType wantedType,
        CollabInstrumentSummary instrument,
        CollabBranch branch,
        String customSpecialty,
        String title,
        String description,
        CollabCitySummary city,
        List<String> genres,
        Instant scheduledAt,
        Instant expiresAt,
        Long feeAmountMinor,
        String currency,
        CollabFeeStatus feeStatus,
        Instant publishedAt,
        Instant closedAt,
        Instant createdAt,
        CollabActorSummary publisher,
        long applicationCount,
        boolean ownedByMe,
        boolean appliedByMe,
        boolean savedByMe
) {}
