package com.berkayb.soundconnect.modules.studio.reservation.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record StudioManualBlockReleaseRequest(
        @NotNull @Min(0) Long expectedVersion,
        @Size(max = 200) String reason
) {
}
