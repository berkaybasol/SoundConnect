package com.berkayb.soundconnect.modules.backline.catalog.dto;

import com.berkayb.soundconnect.modules.backline.catalog.model.BacklineCategoryRequestType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record BacklineCategoryRequestCreateRequest(
        @NotNull UUID clientRequestId,
        @NotNull BacklineCategoryRequestType type,
        @NotBlank @Size(max = 160) String name,
        UUID parentCategoryId,
        @Size(max = 10) List<@NotBlank @Size(max = 160) String> proposedChildren,
        @Size(max = 300) String requesterNote
) {
}
