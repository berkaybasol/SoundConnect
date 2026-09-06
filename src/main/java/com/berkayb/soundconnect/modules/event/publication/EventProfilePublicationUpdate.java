package com.berkayb.soundconnect.modules.event.publication;

import com.berkayb.soundconnect.modules.event.enums.PerformerType;
import jakarta.validation.constraints.*;
import java.util.UUID;

public record EventProfilePublicationUpdate(@NotNull PerformerType targetType, @NotNull UUID targetId,
        @NotNull Boolean visible, @NotNull @Min(0) @Max(Long.MAX_VALUE - 1) Long version) { }
