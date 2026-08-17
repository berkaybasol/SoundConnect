package com.berkayb.soundconnect.modules.collab.repository;

import com.berkayb.soundconnect.modules.collab.enums.CollabReportReason;
import com.berkayb.soundconnect.modules.collab.enums.CollabReportStatus;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class CollabReportRepositoryContractTest {

    @Test
    void adminPageUsesTheImmutableSnapshotWithoutFetchingMutableListingEvidence() throws Exception {
        Method method = CollabReportRepository.class.getMethod(
                "findAdminPage", CollabReportStatus.class, CollabReportReason.class, Pageable.class);
        EntityGraph graph = method.getAnnotation(EntityGraph.class);

        assertThat(graph).isNotNull();
        assertThat(Arrays.asList(graph.attributePaths()))
                .containsExactlyInAnyOrder(
                        "listing",
                        "reporterUser",
                        "reviewedByUser")
                .doesNotContain(
                        "listing.publisherActor",
                        "listing.instrument",
                        "listing.city",
                        "listing.genres");
    }
}
