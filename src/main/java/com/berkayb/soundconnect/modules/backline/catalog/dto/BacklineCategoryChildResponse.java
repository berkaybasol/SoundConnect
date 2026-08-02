package com.berkayb.soundconnect.modules.backline.catalog.dto;

import java.util.UUID;

public record BacklineCategoryChildResponse(
        UUID id,
        String code,
        String name,
        String iconKey,
        int sortOrder
) {
}
