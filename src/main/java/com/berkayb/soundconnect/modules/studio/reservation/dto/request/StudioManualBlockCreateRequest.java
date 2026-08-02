package com.berkayb.soundconnect.modules.studio.reservation.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

import static com.berkayb.soundconnect.modules.studio.reservation.support.StudioReservationTimeProvider.MAX_MANUAL_BLOCK_DURATION_HOURS;

public record StudioManualBlockCreateRequest(
        @NotNull LocalDate date,
        @NotNull LocalTime startTime,
        @NotNull @Min(1) @Max(MAX_MANUAL_BLOCK_DURATION_HOURS) Integer durationHours,
        @NotNull UUID clientRequestId
) {
}
