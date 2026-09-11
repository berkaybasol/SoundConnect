package com.berkayb.soundconnect.modules.feed.musician.provider;

import com.berkayb.soundconnect.modules.feed.musician.candidate.MusicianFeedCandidateRequest;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

class MusicianFeedProviderTransactionMetadataTest {

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
