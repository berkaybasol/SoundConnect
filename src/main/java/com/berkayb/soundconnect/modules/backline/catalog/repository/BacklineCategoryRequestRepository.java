package com.berkayb.soundconnect.modules.backline.catalog.repository;

import com.berkayb.soundconnect.modules.backline.catalog.entity.BacklineCategoryRequest;
import com.berkayb.soundconnect.modules.backline.catalog.model.BacklineCategoryRequestStatus;
import com.berkayb.soundconnect.modules.backline.catalog.model.BacklineCategoryRequestType;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface BacklineCategoryRequestRepository extends JpaRepository<BacklineCategoryRequest, UUID> {

    @EntityGraph(attributePaths = "studioProfile")
    Page<BacklineCategoryRequest> findByStudioProfileId(UUID studioProfileId, Pageable pageable);

    @EntityGraph(attributePaths = "studioProfile")
    Page<BacklineCategoryRequest> findByStatus(BacklineCategoryRequestStatus status, Pageable pageable);

    @Override
    @EntityGraph(attributePaths = "studioProfile")
    Page<BacklineCategoryRequest> findAll(Pageable pageable);

    Optional<BacklineCategoryRequest> findByStudioProfileIdAndClientRequestId(
            UUID studioProfileId,
            UUID clientRequestId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from BacklineCategoryRequest r where r.id = :requestId")
    Optional<BacklineCategoryRequest> findByIdForUpdate(@Param("requestId") UUID requestId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select request from BacklineCategoryRequest request
            where request.id = :requestId
              and request.studioProfile.user.id = :ownerUserId
            """)
    Optional<BacklineCategoryRequest> findOwnedByIdForUpdate(
            @Param("requestId") UUID requestId,
            @Param("ownerUserId") UUID ownerUserId
    );

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
