package com.berkayb.soundconnect.modules.collab.repository;

import com.berkayb.soundconnect.modules.collab.entity.CollabReport;
import com.berkayb.soundconnect.modules.collab.enums.CollabReportReason;
import com.berkayb.soundconnect.modules.collab.enums.CollabReportStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.*;

public interface CollabReportRepository extends JpaRepository<CollabReport, UUID> {
    Optional<CollabReport> findByReporterUserIdAndClientRequestId(UUID reporterUserId, UUID clientRequestId);
    Optional<CollabReport> findByReporterUserIdAndListingId(UUID reporterUserId, UUID listingId);

    @Query("select r.listing.id from CollabReport r where r.id = :id")
    Optional<UUID> findListingId(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from CollabReport r where r.id = :id")
    Optional<CollabReport> findByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select r from CollabReport r
             where r.listing.id = :listingId
               and r.status = :status
             order by r.reportedAt, r.id
            """)
    List<CollabReport> findByListingIdAndStatusForUpdate(@Param("listingId") UUID listingId,
                                                         @Param("status") CollabReportStatus status);

    // Listing evidence is immutable JSON on the report. Only identity/current
    // status and report principals are fetched; mutable listing detail joins are
    // intentionally absent so pagination stays database-native and N+1-free.
    @EntityGraph(attributePaths = {
            "listing",
            "reporterUser",
            "reviewedByUser"
    })
    @Query("""
            select r from CollabReport r
             where (:status is null or r.status = :status)
               and (:reason is null or r.reason = :reason)
            """)
    Page<CollabReport> findAdminPage(@Param("status") CollabReportStatus status,
                                     @Param("reason") CollabReportReason reason,
                                     Pageable pageable);
}
