package com.berkayb.soundconnect.modules.studio.equipment.dto;

import com.berkayb.soundconnect.modules.studio.equipment.model.EquipmentAvailabilityBucket;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record EquipmentAvailabilityCommandResponse(
        UUID commandId,
        UUID clientRequestId,
        UUID equipmentId,
        LocalDate startDate,
        LocalDate endDate,
        EquipmentAvailabilityBucket sourceBucket,
        EquipmentAvailabilityBucket targetBucket,
        int quantity,
        Instant appliedAt,
        boolean replayed
) {
}
