package com.berkayb.soundconnect.modules.studio.equipment.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record EquipmentAvailabilityRangeResponse(
        UUID equipmentId,
        LocalDate startDate,
        LocalDate endDate,
        List<EquipmentAvailabilityDayResponse> days
) {
}
