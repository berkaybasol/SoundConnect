package com.berkayb.soundconnect.modules.backline.catalog.dto;

import java.util.UUID;

public record BacklineCategoryRequestChildResponse(
        String name,
        int position,
        UUID resolvedCategoryId
) {
}
