package com.berkayb.soundconnect.modules.collab.event;

import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationInboundEvent;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationProducer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class CollabNotificationListenerTest {

    @Test
    void mapsAndPublishesTheSnapshotAfterCommit() throws Exception {
        NotificationProducer producer = mock(NotificationProducer.class);
        CollabNotificationListener listener = new CollabNotificationListener(producer);
        UUID recipientId = UUID.randomUUID();
        UUID listingId = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2026-08-11T00:00:00Z");
        CollabNotificationEvent event = CollabNotificationEvent.create(
                recipientId,
                NotificationType.COLLAB_APPLICATION_RECEIVED,
                "Yeni basvuru",
                "Ilanina yeni bir basvuru geldi.",
                "APPLICATION_RECEIVED",
                Map.of("listingId", listingId),
                occurredAt
        );

        listener.onCollabNotification(event);

        ArgumentCaptor<NotificationInboundEvent> captor =
                ArgumentCaptor.forClass(NotificationInboundEvent.class);
        verify(producer).publish(captor.capture());
        NotificationInboundEvent published = captor.getValue();
        assertThat(published.recipientId()).isEqualTo(recipientId);
        assertThat(published.type()).isEqualTo(NotificationType.COLLAB_APPLICATION_RECEIVED);
        assertThat(published.title()).isEqualTo("Yeni basvuru");
        assertThat(published.message()).isEqualTo("Ilanina yeni bir basvuru geldi.");
        assertThat(published.payload()).isEqualTo(event.payload());
        assertThat(published.emailForce()).isFalse();
        assertThat(published.occurredAt()).isEqualTo(occurredAt);

        TransactionalEventListener annotation = CollabNotificationListener.class
                .getMethod("onCollabNotification", CollabNotificationEvent.class)
                .getAnnotation(TransactionalEventListener.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
        assertThat(annotation.fallbackExecution()).isFalse();
    }

    @Test
    void producerFailureDoesNotEscapeTheAfterCommitListener() {
        NotificationProducer producer = mock(NotificationProducer.class);
        doThrow(new IllegalStateException("broker unavailable"))
                .when(producer)
                .publish(any(NotificationInboundEvent.class));
        CollabNotificationListener listener = new CollabNotificationListener(producer);
        CollabNotificationEvent event = CollabNotificationEvent.create(
                UUID.randomUUID(),
                NotificationType.COLLAB_LISTING_EXPIRED,
                "Ilan suresi doldu",
                "Collab ilaninin suresi doldu.",
                "LISTING_EXPIRED",
                Map.of("listingId", UUID.randomUUID()),
                Instant.now()
        );

        assertThatCode(() -> listener.onCollabNotification(event))
                .doesNotThrowAnyException();
        verify(producer).publish(any(NotificationInboundEvent.class));
    }
}
