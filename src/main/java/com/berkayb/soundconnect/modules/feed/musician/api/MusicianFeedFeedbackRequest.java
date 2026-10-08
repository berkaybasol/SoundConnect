package com.berkayb.soundconnect.modules.feed.musician.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MusicianFeedFeedbackRequest(
        @NotNull MusicianFeedFeedbackAction action,
        @Size(max = 500) String reason,
        @NotBlank @Size(max = 4096) String impressionToken
) {
    public MusicianFeedFeedbackRequest(MusicianFeedFeedbackAction action, String reason) {
        this(action, reason, "test-impression-token");
    }
}
