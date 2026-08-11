package com.berkayb.soundconnect.modules.collab.dto.request;

import jakarta.validation.constraints.*;

import java.util.UUID;

public record CollabReviewCreateRequest(
        @NotNull UUID clientRequestId,
        @Min(1) @Max(5) int rating,
        @Size(max = 500) String comment
) {}
