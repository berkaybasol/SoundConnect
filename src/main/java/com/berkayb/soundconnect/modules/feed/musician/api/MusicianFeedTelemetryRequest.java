package com.berkayb.soundconnect.modules.feed.musician.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public record MusicianFeedTelemetryRequest(
        @NotNull UUID clientEventId,
        @NotBlank @Size(max = 4096) String impressionToken,
        @NotNull MusicianFeedTelemetryEventType eventType,
        Instant occurredAt
) { }
