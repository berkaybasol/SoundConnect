package com.berkayb.soundconnect.modules.studio.reservation.event;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import static org.assertj.core.api.Assertions.assertThat;

class StudioReservationNotificationListenerTest {

    @Test
    void deliveryIsRegisteredOnlyAfterTheDomainTransactionCommits() throws Exception {
        TransactionalEventListener listener = StudioReservationNotificationListener.class
                .getMethod("onReservationNotification", StudioReservationNotificationEvent.class)
                .getAnnotation(TransactionalEventListener.class);

        assertThat(listener).isNotNull();
        assertThat(listener.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
        assertThat(listener.fallbackExecution()).isFalse();
    }
}
