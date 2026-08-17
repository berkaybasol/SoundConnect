package com.berkayb.soundconnect.modules.collab.support;

import com.berkayb.soundconnect.modules.collab.entity.Collab;
import com.berkayb.soundconnect.modules.collab.repository.CollabRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.jpa.domain.Specification;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CollabPublisherOwnershipGuardTest {
    @Mock CollabRepository listingRepository;
    @InjectMocks CollabPublisherOwnershipGuard guard;

    @Test
    void validDatabasePredicateAllowsTheListing() {
        Collab listing = listing();
        when(listingRepository.exists(any(Specification.class))).thenReturn(true);

        assertThatCode(() -> guard.requireValid(listing)).doesNotThrowAnyException();
    }

    @Test
    void missingOwnershipPredicateResultFailsClosedAsNotFound() {
        Collab listing = listing();
        when(listingRepository.exists(any(Specification.class))).thenReturn(false);

        assertThatThrownBy(() -> guard.requireValid(listing))
                .isInstanceOfSatisfying(SoundConnectException.class,
                        error -> assertThat(error.getErrorType()).isEqualTo(ErrorType.COLLAB_NOT_FOUND));
    }

    private static Collab listing() {
        Collab listing = new Collab();
        listing.setId(UUID.randomUUID());
        return listing;
    }
}
