package com.berkayb.soundconnect.modules.collab.spec;

import com.berkayb.soundconnect.modules.collab.entity.Collab;
import com.berkayb.soundconnect.modules.collab.entity.CollabSavedListing;
import com.berkayb.soundconnect.modules.collab.enums.CollabListingStatus;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.UUID;

public final class CollabSavedListingSpecifications {
    private CollabSavedListingSpecifications() {
    }

    public static Specification<CollabSavedListing> visible(UUID userId,
                                                             CollabListingStatus status,
                                                             Instant now) {
        return (root, query, cb) -> {
            Join<CollabSavedListing, Collab> listing = root.join("listing", JoinType.INNER);
            return cb.and(
                    cb.equal(root.get("user").get("id"), userId),
                    cb.equal(listing.get("status"), status),
                    cb.or(cb.isNull(listing.get("expiresAt")),
                            cb.greaterThan(listing.<Instant>get("expiresAt"), now)),
                    CollabSpecifications.publisherOwnershipValidPredicate(listing, query, cb));
        };
    }
}
