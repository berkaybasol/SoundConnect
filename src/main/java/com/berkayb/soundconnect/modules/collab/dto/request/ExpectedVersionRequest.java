package com.berkayb.soundconnect.modules.collab.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record ExpectedVersionRequest(@NotNull @PositiveOrZero Long expectedVersion) {}
