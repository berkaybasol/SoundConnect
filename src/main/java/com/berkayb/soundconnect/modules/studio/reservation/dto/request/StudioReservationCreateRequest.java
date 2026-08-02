package com.berkayb.soundconnect.modules.studio.reservation.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

public record StudioReservationCreateRequest(
        @NotNull UUID roomId,
        @NotNull LocalDate date,
        @NotNull LocalTime startTime,
        @NotNull @Min(1) @Max(4) Integer durationHours,
        @NotBlank(message = "Telefon numarası zorunludur")
        @Size(min = 11, max = 24, message = "Telefon numarası geçersizdir")
        @Pattern(
                regexp = "^0[0-9() .-]+$",
                message = "Telefon numarası 0 ile başlamalıdır"
        )
        String contactPhone,
        @NotNull UUID clientRequestId
) {
}
