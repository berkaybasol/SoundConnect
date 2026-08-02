package com.berkayb.soundconnect.modules.backline.catalog.repository;

import com.berkayb.soundconnect.modules.backline.catalog.entity.BacklineCategoryRequest;
import com.berkayb.soundconnect.modules.backline.catalog.model.BacklineCategoryRequestStatus;
import com.berkayb.soundconnect.modules.backline.catalog.model.BacklineCategoryRequestType;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface BacklineCategoryRequestRepository extends JpaRepository<BacklineCategoryRequest, UUID> {

    Page<BacklineCategoryRequest> findByStudioProfileId(UUID studioProfileId, Pageable pageable);

    Page<BacklineCategoryRequest> findByStatus(BacklineCategoryRequestStatus status, Pageable pageable);

    Optional<BacklineCategoryRequest> findByStudioProfileIdAndClientRequestId(
            UUID studioProfileId,
            UUID clientRequestId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from BacklineCategoryRequest r where r.id = :requestId")
    Optional<BacklineCategoryRequest> findByIdForUpdate(@Param("requestId") UUID requestId);

    boolean existsByStudioProfileIdAndTypeAndNormalizedRequestedNameAndStatusAndParentCategoryIsNull(
            UUID studioProfileId,
            BacklineCategoryRequestType type,
            String normalizedRequestedName,
            BacklineCategoryRequestStatus status
    );

    boolean existsByStudioProfileIdAndTypeAndNormalizedRequestedNameAndStatusAndParentCategoryId(
            UUID studioProfileId,
            BacklineCategoryRequestType type,
            String normalizedRequestedName,
            BacklineCategoryRequestStatus status,
            UUID parentCategoryId
    );
}
