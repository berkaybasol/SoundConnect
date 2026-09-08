package com.berkayb.soundconnect.modules.venue.suggestion;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

@com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = VenueSuggestionRequestDeserializer.class)
public record VenueSuggestionRequest(@NotNull UUID requestId, @NotNull @Size(max = 400) String venueName,
        @NotNull UUID cityId, @NotNull UUID districtId, @NotNull LiveMusic liveMusic) {
    public enum LiveMusic { YES, NO, UNKNOWN }
}
