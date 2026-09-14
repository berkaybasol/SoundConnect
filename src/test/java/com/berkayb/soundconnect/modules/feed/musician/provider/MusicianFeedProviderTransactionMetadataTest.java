package com.berkayb.soundconnect.modules.feed.musician.provider;

import com.berkayb.soundconnect.modules.feed.musician.candidate.MusicianFeedCandidateRequest;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

class MusicianFeedProviderTransactionMetadataTest {

    @Test
    void announcementFeedReadsBoundJdbcWorkWithinTheirTransaction() throws Exception {
        Class<?> type = com.berkayb.soundconnect.modules.promotion.announcement.AnnouncementReadService.class;
        for (var method : type.getDeclaredMethods()) {
            if (!java.util.Set.of("directory", "findForFeed", "findForFeedBatch", "findForFeedByIds", "project")
                    .contains(method.getName())) continue;
            var transaction = method.getAnnotation(Transactional.class);
            assertThat(transaction).as(method.getName()).isNotNull();
            assertThat(transaction.timeout()).as(method.getName()).isEqualTo(5);
            assertThat(transaction.isolation()).isEqualTo(org.springframework.transaction.annotation.Isolation.REPEATABLE_READ);
        }
    }

    @Test
    void overthinkingProjectionProvidersUseWritableRequiresNewBoundaries() throws Exception {
        assertLockCapableBoundary(MusicianFeedOverthinkingShareCandidateProvider.class);
        assertLockCapableBoundary(MusicianFeedMediaActivityCandidateProvider.class);
    }

    private void assertLockCapableBoundary(Class<?> providerType) throws Exception {
        Transactional transaction = providerType.getMethod(
                        "findCandidates", MusicianFeedCandidateRequest.class)
                .getAnnotation(Transactional.class);

        assertThat(transaction).as(providerType.getSimpleName()).isNotNull();
        assertThat(transaction.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
        assertThat(transaction.readOnly()).isFalse();
        assertThat(transaction.timeout()).isEqualTo(5);
    }
}
