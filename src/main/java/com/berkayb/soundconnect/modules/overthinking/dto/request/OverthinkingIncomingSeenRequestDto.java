package com.berkayb.soundconnect.modules.overthinking.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record OverthinkingIncomingSeenRequestDto(@NotNull @Min(0) Long revision) { }
