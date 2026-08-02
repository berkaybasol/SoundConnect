package com.berkayb.soundconnect.modules.backline.catalog.dto;

import com.berkayb.soundconnect.modules.backline.catalog.model.BacklineCategoryReviewDecision;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record BacklineCategoryReviewRequest(
        @NotNull BacklineCategoryReviewDecision decision,
        @Size(max = 500) String note
) {
}
