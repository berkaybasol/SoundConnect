package com.berkayb.soundconnect.modules.collab.spec;

import com.berkayb.soundconnect.modules.collab.entity.Collab;
import com.berkayb.soundconnect.modules.collab.entity.CollabSavedListing;
import com.berkayb.soundconnect.modules.collab.enums.CollabListingStatus;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CollabSavedListingSpecificationsTest {

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    void visibleSavedPageUsesTheSharedPublisherOwnershipPredicateBeforePagination() {
        Root<CollabSavedListing> root = mock(Root.class, RETURNS_DEEP_STUBS);
        CriteriaQuery<?> query = mock(CriteriaQuery.class, RETURNS_DEEP_STUBS);
        CriteriaBuilder cb = mock(CriteriaBuilder.class, RETURNS_DEEP_STUBS);
        Join<CollabSavedListing, Collab> listing = mock(Join.class, RETURNS_DEEP_STUBS);
        when(root.join("listing", JoinType.INNER)).thenReturn((Join) listing);

        CollabSavedListingSpecifications.visible(
                        UUID.randomUUID(), CollabListingStatus.OPEN, Instant.now())
                .toPredicate(root, query, cb);

        verify(root).join("listing", JoinType.INNER);
        verify(query, times(4)).subquery(Integer.class);
        verify(cb).isTrue(any(Expression.class));
    }
}
