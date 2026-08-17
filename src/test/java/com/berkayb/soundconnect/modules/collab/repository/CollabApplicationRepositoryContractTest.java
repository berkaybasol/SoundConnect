package com.berkayb.soundconnect.modules.collab.repository;

import com.berkayb.soundconnect.modules.collab.enums.CollabApplicationStatus;
import com.berkayb.soundconnect.modules.collab.enums.CollabListingStatus;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CollabApplicationRepositoryContractTest {

    @Test
    void applicantExpiryReconciliationUsesTheGlobalListingLockOrder() throws Exception {
        Method method = CollabApplicationRepository.class.getMethod(
                "findDueListingIdsForApplicant",
                UUID.class,
                CollabApplicationStatus.class,
                CollabListingStatus.class,
                Instant.class,
                Pageable.class);
        Query query = method.getAnnotation(Query.class);

        assertThat(query).isNotNull();
        assertThat(query.value().replaceAll("\\s+", " ").strip())
                .contains("order by a.listing.expiresAt, a.listing.id")
                .doesNotContain("select distinct");
    }
}
