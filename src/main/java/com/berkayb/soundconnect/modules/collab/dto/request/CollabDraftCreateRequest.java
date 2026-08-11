package com.berkayb.soundconnect.modules.collab.dto.request;

import com.berkayb.soundconnect.modules.collab.enums.*;
import jakarta.validation.constraints.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CollabDraftCreateRequest(
        @NotNull UUID clientRequestId,
        @NotNull UUID publisherActorId,
        @NotNull CollabCadence cadence,
        @NotNull CollabWantedType wantedType,
        UUID instrumentId,
        CollabBranch branch,
        @Size(max = 80) String customSpecialty,
        @NotBlank @Size(min = 5, max = 100) String title,
        @NotBlank @Size(min = 20, max = 500) String description,
        @NotNull UUID cityId,
        @Size(max = 3) List<@NotBlank @Size(max = 40) String> genres,
        Instant scheduledAt,
        @Positive Long feeAmountMinor,
        @Pattern(regexp = "^[A-Z]{3}$") String currency
) {}
