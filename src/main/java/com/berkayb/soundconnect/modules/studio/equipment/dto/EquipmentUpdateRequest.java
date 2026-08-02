package com.berkayb.soundconnect.modules.studio.equipment.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record EquipmentUpdateRequest(
        @NotNull @PositiveOrZero Long expectedVersion,
        @NotNull UUID leafCategoryId,
        @NotBlank @Size(max = 100) String name,
        @Size(max = 60) String brand,
        @Size(max = 60) String model,
        @Size(max = 300) String description,
        @Min(1) @Max(999) int totalQuantity,
        @NotNull @Size(max = 12) List<@NotBlank @Size(max = 60) String> features,
        @NotNull @Size(max = 5) List<@NotNull UUID> photoMediaIds
) {
}
