package com.berkayb.soundconnect.modules.studio.reservation.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record StudioVersionRequest(@NotNull @Min(0) Long expectedVersion) {
}
