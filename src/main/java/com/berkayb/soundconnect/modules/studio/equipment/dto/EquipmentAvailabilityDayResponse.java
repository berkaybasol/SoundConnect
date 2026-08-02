package com.berkayb.soundconnect.modules.studio.equipment.dto;

import com.berkayb.soundconnect.modules.studio.equipment.model.EquipmentAvailabilityStatus;

import java.time.LocalDate;

public record EquipmentAvailabilityDayResponse(
        LocalDate date,
        int totalQuantity,
        int availableQuantity,
        int busyQuantity,
        int maintenanceQuantity,
        EquipmentAvailabilityStatus status
) {
}
