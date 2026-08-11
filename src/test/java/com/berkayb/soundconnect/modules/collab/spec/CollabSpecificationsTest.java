package com.berkayb.soundconnect.modules.collab.spec;

import com.berkayb.soundconnect.modules.collab.dto.request.CollabFilterRequest;
import com.berkayb.soundconnect.modules.collab.entity.Collab;
import com.berkayb.soundconnect.modules.collab.enums.*;
import jakarta.persistence.criteria.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.*;

import static org.mockito.Mockito.*;

class CollabSpecificationsTest {

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    void combinedInstrumentAndBranchFilterUsesLeftJoinAndOrPredicate() {
        Root<Collab> root = mock(Root.class, RETURNS_DEEP_STUBS);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        CriteriaBuilder cb = mock(CriteriaBuilder.class, RETURNS_DEEP_STUBS);
        Join instrumentJoin = mock(Join.class);
        Path instrumentIdPath = mock(Path.class);
        Path branchPath = mock(Path.class);
        Predicate instrumentPredicate = mock(Predicate.class);
        Predicate branchPredicate = mock(Predicate.class);
        Predicate combinedPredicate = mock(Predicate.class);
        UUID instrumentId = UUID.randomUUID();
        Set<CollabBranch> branches = Set.of(CollabBranch.VOCAL);

        when(root.join("instrument", JoinType.LEFT)).thenReturn(instrumentJoin);
        when(instrumentJoin.get("id")).thenReturn(instrumentIdPath);
        when(instrumentIdPath.in(Set.of(instrumentId))).thenReturn(instrumentPredicate);
        when(root.get("branch")).thenReturn(branchPath);
        when(branchPath.in(branches)).thenReturn(branchPredicate);
        when(cb.or(instrumentPredicate, branchPredicate)).thenReturn(combinedPredicate);

        CollabFilterRequest filter = new CollabFilterRequest(
                CollabCadence.REGULAR, null, CollabWantedType.MUSICIAN,
                Set.of(instrumentId), branches, null, null, null);
        Specification<Collab> specification = CollabSpecifications.discovery(filter, Instant.now());
        specification.toPredicate(root, query, cb);

        verify(root).join("instrument", JoinType.LEFT);
        verify(cb).or(instrumentPredicate, branchPredicate);
    }
}
