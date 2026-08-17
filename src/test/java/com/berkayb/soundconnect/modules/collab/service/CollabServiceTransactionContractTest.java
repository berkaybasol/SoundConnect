package com.berkayb.soundconnect.modules.collab.service;

import com.berkayb.soundconnect.modules.collab.exception.CollabExpiredListingNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CollabServiceTransactionContractTest {

    @Test
    void hiddenDueDetailCommitsTheExpiryBeforeReturningNotFound() throws Exception {
        Method method = CollabService.class.getMethod("detail", UUID.class, UUID.class);
        Transactional transactional = method.getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(Arrays.asList(transactional.noRollbackFor()))
                .contains(CollabExpiredListingNotFoundException.class);
    }
}
