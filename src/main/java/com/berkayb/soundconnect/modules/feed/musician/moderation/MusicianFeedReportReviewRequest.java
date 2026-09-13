package com.berkayb.soundconnect.modules.feed.musician.moderation;

import jakarta.validation.constraints.*;
import java.util.UUID;

public record MusicianFeedReportReviewRequest(
        @NotNull UUID clientRequestId,
        @NotNull @PositiveOrZero Long expectedVersion,
        @NotNull MusicianFeedReportDecision decision,
        @NotBlank @Size(min = 5, max = 500) String resolutionNote) { }
