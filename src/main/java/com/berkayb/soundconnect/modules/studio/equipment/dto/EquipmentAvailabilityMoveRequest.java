package com.berkayb.soundconnect.modules.studio.equipment.dto;

import com.berkayb.soundconnect.modules.studio.equipment.model.EquipmentAvailabilityBucket;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.UUID;

public record EquipmentAvailabilityMoveRequest(
        @NotNull UUID clientRequestId,
        @NotNull LocalDate startDate,
        @NotNull LocalDate endDate,
        @NotNull EquipmentAvailabilityBucket sourceBucket,
        @NotNull EquipmentAvailabilityBucket targetBucket,
        @Min(1) int quantity
) {
}
