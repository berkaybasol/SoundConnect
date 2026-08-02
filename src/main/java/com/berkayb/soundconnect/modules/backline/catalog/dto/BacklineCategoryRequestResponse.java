package com.berkayb.soundconnect.modules.backline.catalog.dto;

import com.berkayb.soundconnect.modules.backline.catalog.model.BacklineCategoryRequestStatus;
import com.berkayb.soundconnect.modules.backline.catalog.model.BacklineCategoryRequestType;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record BacklineCategoryRequestResponse(
        UUID id,
        UUID clientRequestId,
        UUID studioProfileId,
        BacklineCategoryRequestType type,
        String requestedName,
        UUID parentCategoryId,
        String parentCategoryName,
        List<BacklineCategoryRequestChildResponse> proposedChildren,
        String requesterNote,
        BacklineCategoryRequestStatus status,
        UUID resolvedRootCategoryId,
        UUID resolvedCategoryId,
        UUID reviewedByUserId,
        Instant reviewedAt,
        String decisionNote,
        LocalDateTime createdAt,
        Instant createdAtUtc
) {
}
