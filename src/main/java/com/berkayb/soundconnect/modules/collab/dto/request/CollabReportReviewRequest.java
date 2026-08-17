package com.berkayb.soundconnect.modules.collab.dto.request;

import com.berkayb.soundconnect.modules.collab.enums.CollabReportDecision;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record CollabReportReviewRequest(
        @NotNull CollabReportDecision decision,
        @NotNull @PositiveOrZero Long expectedVersion,
        @NotBlank @Size(min = 5, max = 500) String resolutionNote
) {}
