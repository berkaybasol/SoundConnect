package com.berkayb.soundconnect.modules.studio.room.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.AssertTrue;

import java.util.List;
import java.util.UUID;

public record StudioRoomUpdateRequest(
        @NotNull @Min(0) Long expectedVersion,
        @NotBlank @Size(max = 100) String name,
        @Size(max = 60) String shortDescription,
        @NotNull @Min(1) @Max(100) Integer capacity,
        @Min(1) @Max(100) Integer minimumCapacity,
        @Positive @Max(100_000_000) Long hourlyPriceMinor,
        @Pattern(regexp = "^[A-Z]{3}$") String currency,
        @NotNull Boolean reservationApprovalRequired,
        @Size(max = 8) List<@NotBlank @Size(max = 60) String> features,
        @Size(max = 10) List<@NotNull UUID> photoMediaIds
) {
    public StudioRoomUpdateRequest(
            Long expectedVersion,
            String name,
            String shortDescription,
            Integer capacity,
            Long hourlyPriceMinor,
            String currency,
            Boolean reservationApprovalRequired,
            List<String> features,
            List<UUID> photoMediaIds
    ) {
        this(
                expectedVersion,
                name,
                shortDescription,
                capacity,
                capacity,
                hourlyPriceMinor,
                currency,
                reservationApprovalRequired,
                features,
                photoMediaIds
        );
    }

    @AssertTrue(message = "minimumCapacity must be less than or equal to capacity")
    public boolean isCapacityRangeValid() {
        return minimumCapacity == null || capacity == null || minimumCapacity <= capacity;
    }
}
