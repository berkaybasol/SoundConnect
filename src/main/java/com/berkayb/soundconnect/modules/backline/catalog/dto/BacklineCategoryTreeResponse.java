package com.berkayb.soundconnect.modules.backline.catalog.dto;

import java.util.List;
import java.util.UUID;

public record BacklineCategoryTreeResponse(
        UUID id,
        String code,
        String name,
        String iconKey,
        int sortOrder,
        List<BacklineCategoryChildResponse> children
) {
}
