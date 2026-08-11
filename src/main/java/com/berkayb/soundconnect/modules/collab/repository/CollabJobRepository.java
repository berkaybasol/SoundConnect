package com.berkayb.soundconnect.modules.collab.repository;

import com.berkayb.soundconnect.modules.collab.entity.CollabJob;
import com.berkayb.soundconnect.modules.collab.enums.CollabJobStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.util.*;

public interface CollabJobRepository extends JpaRepository<CollabJob, UUID> {
    Optional<CollabJob> findByApplicationId(UUID applicationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from CollabJob j where j.id = :id")
    Optional<CollabJob> findByIdForUpdate(@Param("id") UUID id);

    @EntityGraph(attributePaths = {"listing", "listing.city", "listing.instrument", "listing.publisherActor", "listing.owner", "publisherActor", "applicantActor", "publisherUser", "applicantUser"})
    @Query("select j from CollabJob j where j.publisherUser.id = :userId or j.applicantUser.id = :userId")
    Page<CollabJob> findMine(@Param("userId") UUID userId, Pageable pageable);

    @EntityGraph(attributePaths = {"listing", "listing.city", "listing.instrument", "listing.publisherActor", "listing.owner", "publisherActor", "applicantActor", "publisherUser", "applicantUser"})
    @Query("select j from CollabJob j where (j.publisherUser.id = :userId or j.applicantUser.id = :userId) and j.status = :status")
    Page<CollabJob> findMineByStatus(@Param("userId") UUID userId, @Param("status") CollabJobStatus status, Pageable pageable);
}
