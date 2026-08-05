package com.berkayb.soundconnect.modules.studio.equipment.dto;

public record EquipmentInventorySummaryResponse(
        long totalQuantity,
        long availableQuantity,
        long busyQuantity,
        long maintenanceQuantity
) {
}
