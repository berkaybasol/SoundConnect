package com.berkayb.soundconnect.modules.collab.repository;

import com.berkayb.soundconnect.modules.collab.entity.CollabApplication;
import com.berkayb.soundconnect.modules.collab.enums.CollabApplicationStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.*;

public interface CollabApplicationRepository extends JpaRepository<CollabApplication, UUID> {
    @EntityGraph(attributePaths = {"listing", "listing.publisherActor", "listing.city", "listing.instrument", "listing.owner", "applicantActor", "applicantUser"})
    Optional<CollabApplication> findByApplicantUserIdAndClientRequestId(UUID applicantUserId, UUID clientRequestId);

    boolean existsByListingId(UUID listingId);
    boolean existsByListingIdAndApplicantActorId(UUID listingId, UUID applicantActorId);
    boolean existsByListingIdAndApplicantUserId(UUID listingId, UUID applicantUserId);
    long countByListingId(UUID listingId);

    @Query("select a.listing.id from CollabApplication a where a.applicantUser.id = :userId and a.listing.id in :listingIds")
    Set<UUID> findAppliedListingIds(@Param("userId") UUID userId, @Param("listingIds") Collection<UUID> listingIds);

    @Query("select a.listing.id from CollabApplication a where a.applicantUser.id = :userId " +
            "and a.status = :applicationStatus and a.listing.status = :listingStatus and a.listing.expiresAt <= :now " +
            "order by a.listing.expiresAt, a.listing.id")
    List<UUID> findDueListingIdsForApplicant(@Param("userId") UUID userId,
                                             @Param("applicationStatus") CollabApplicationStatus applicationStatus,
                                             @Param("listingStatus") com.berkayb.soundconnect.modules.collab.enums.CollabListingStatus listingStatus,
                                             @Param("now") Instant now, Pageable pageable);

    @Query("select a.listing.id as listingId, count(a.id) as applicationCount from CollabApplication a " +
            "where a.listing.id in :listingIds group by a.listing.id")
    List<CollabListingApplicationCount> countByListingIds(@Param("listingIds") Collection<UUID> listingIds);

    @Query("select a.listing.id from CollabApplication a where a.id = :id")
    Optional<UUID> findListingId(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from CollabApplication a where a.id = :id")
    Optional<CollabApplication> findByIdForUpdate(@Param("id") UUID id);

    @EntityGraph(attributePaths = {"listing", "listing.publisherActor", "listing.city", "listing.instrument", "listing.owner", "applicantActor", "applicantUser"})
    Page<CollabApplication> findByListingId(UUID listingId, Pageable pageable);
    @EntityGraph(attributePaths = {"listing", "listing.publisherActor", "listing.city", "listing.instrument", "listing.owner", "applicantActor", "applicantUser"})
    Page<CollabApplication> findByListingIdAndStatus(UUID listingId, CollabApplicationStatus status, Pageable pageable);

    @EntityGraph(attributePaths = {"listing", "listing.publisherActor", "listing.city", "listing.instrument", "listing.owner", "applicantActor", "applicantUser"})
    Page<CollabApplication> findByApplicantUserId(UUID applicantUserId, Pageable pageable);
    @EntityGraph(attributePaths = {"listing", "listing.publisherActor", "listing.city", "listing.instrument", "listing.owner", "applicantActor", "applicantUser"})
    Page<CollabApplication> findByApplicantUserIdAndStatus(UUID applicantUserId, CollabApplicationStatus status, Pageable pageable);

    @EntityGraph(attributePaths = {"applicantActor", "applicantUser"})
    List<CollabApplication> findByListingIdAndStatus(UUID listingId, CollabApplicationStatus status);

    @Modifying(flushAutomatically = true)
    @Query("""
            update CollabApplication a
               set a.status = :replacement, a.statusChangedAt = :now, a.version = a.version + 1
             where a.listing.id = :listingId and a.status = :pending
               and (:exceptId is null or a.id <> :exceptId)
            """)
    int invalidatePending(@Param("listingId") UUID listingId,
                          @Param("exceptId") UUID exceptId,
                          @Param("pending") CollabApplicationStatus pending,
                          @Param("replacement") CollabApplicationStatus replacement,
                          @Param("now") Instant now);
}
