package com.berkayb.soundconnect.modules.collab.repository;

import com.berkayb.soundconnect.modules.collab.entity.Collab;
import com.berkayb.soundconnect.modules.collab.enums.CollabListingStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.*;

public interface CollabRepository extends JpaRepository<Collab, UUID>, JpaSpecificationExecutor<Collab> {
    @Override
    @EntityGraph(attributePaths = {"publisherActor", "city", "instrument", "owner"})
    Page<Collab> findAll(org.springframework.data.jpa.domain.Specification<Collab> spec, Pageable pageable);

    @EntityGraph(attributePaths = {"publisherActor", "city", "instrument", "owner", "genres"})
    @Query("select c from Collab c where c.id = :id")
    Optional<Collab> findDetailedById(@Param("id") UUID id);

    @EntityGraph(attributePaths = {"publisherActor", "city", "instrument", "owner"})
    Optional<Collab> findByOwnerIdAndClientRequestId(UUID ownerId, UUID clientRequestId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Collab c where c.id = :id")
    Optional<Collab> findByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("""
            select c from Collab c
             where c.id = :id
               and c.status = :status
               and (c.expiresAt is null or c.expiresAt > :now)
            """)
    Optional<Collab> findVisibleOpenByIdForShare(@Param("id") UUID id,
                                                 @Param("status") CollabListingStatus status,
                                                 @Param("now") Instant now);

    @EntityGraph(attributePaths = {"publisherActor", "city", "instrument", "owner"})
    Page<Collab> findByOwnerId(UUID ownerId, Pageable pageable);
    @EntityGraph(attributePaths = {"publisherActor", "city", "instrument", "owner"})
    Page<Collab> findByOwnerIdAndStatus(UUID ownerId, CollabListingStatus status, Pageable pageable);

    @Query("select c.id from Collab c where c.status = :status and c.expiresAt <= :now order by c.expiresAt, c.id")
    List<UUID> findDueIds(@Param("status") CollabListingStatus status, @Param("now") Instant now, Pageable pageable);

    @Query("select c.id from Collab c where c.owner.id = :ownerId and c.status = :status and c.expiresAt <= :now order by c.expiresAt, c.id")
    List<UUID> findDueIdsByOwner(@Param("ownerId") UUID ownerId, @Param("status") CollabListingStatus status,
                                 @Param("now") Instant now, Pageable pageable);
}
