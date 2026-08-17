package com.berkayb.soundconnect.modules.collab.repository;

import com.berkayb.soundconnect.modules.collab.entity.CollabSavedListing;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.util.*;

public interface CollabSavedListingRepository extends JpaRepository<CollabSavedListing, UUID>,
        JpaSpecificationExecutor<CollabSavedListing> {
    long deleteByUserIdAndListingId(UUID userId, UUID listingId);

    @Modifying(flushAutomatically = true)
    @Query("delete from CollabSavedListing s where s.listing.id = :listingId")
    int deleteByListingId(@Param("listingId") UUID listingId);

    @Query("select s.listing.id from CollabSavedListing s where s.user.id = :userId and s.listing.id in :listingIds")
    Set<UUID> findSavedListingIds(@Param("userId") UUID userId, @Param("listingIds") Collection<UUID> listingIds);

    @Modifying
    @Query(value = """
            insert into tbl_collab_saved_listing (id, created_at, updated_at, user_id, collab_id)
            values (:id, current_timestamp, current_timestamp, :userId, :listingId)
            on conflict (user_id, collab_id) do nothing
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("userId") UUID userId,
                       @Param("listingId") UUID listingId);

    @Override
    @EntityGraph(attributePaths = {"listing", "listing.publisherActor", "listing.city", "listing.instrument", "listing.owner"})
    Page<CollabSavedListing> findAll(Specification<CollabSavedListing> specification, Pageable pageable);
}
