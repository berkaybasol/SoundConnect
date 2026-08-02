package com.berkayb.soundconnect.modules.studio.equipment.dto;

import java.util.List;
import java.util.UUID;

public record EquipmentPublicResponse(
        UUID id,
        UUID categoryId,
        String categoryCode,
        String categoryName,
        UUID subcategoryId,
        String subcategoryCode,
        String subcategoryName,
        String categoryIconKey,
        String name,
        String brand,
        String model,
        String description,
        int totalQuantity,
        List<String> features,
        List<PublicEquipmentPhotoResponse> photos,
        EquipmentAvailabilityDayResponse todayAvailability
) {
}
