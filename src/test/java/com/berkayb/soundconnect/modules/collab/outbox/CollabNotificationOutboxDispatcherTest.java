package com.berkayb.soundconnect.modules.collab.outbox;

import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationInboundEvent;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationProducer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CollabNotificationOutboxDispatcherTest {

    @Mock
    private CollabNotificationOutboxService outboxService;

    @Mock
    private NotificationProducer notificationProducer;

    private CollabNotificationOutboxDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new CollabNotificationOutboxDispatcher(outboxService, notificationProducer);
    }

    @Test
    void confirmedPublishIsMarkedPublishedWithTheStableEventId() {
        CollabNotificationOutboxClaim claim = claim(1);
        when(outboxService.claim(any(UUID.class), anyString())).thenReturn(Optional.of(claim));
        when(outboxService.markPublished(claim)).thenReturn(true);

        dispatcher.dispatch(claim.eventId());

        ArgumentCaptor<NotificationInboundEvent> eventCaptor =
                ArgumentCaptor.forClass(NotificationInboundEvent.class);
        verify(notificationProducer).publishConfirmed(eventCaptor.capture());
        assertThat(eventCaptor.getValue().eventId()).isEqualTo(claim.eventId());
        assertThat(eventCaptor.getValue().payload()).isEqualTo(claim.payload());
        verify(outboxService).markPublished(claim);
        verify(outboxService, never()).markFailed(any(), anyString());
    }

    @Test
    void brokerFailureSchedulesRetryAndIsNotMarkedPublished() {
        CollabNotificationOutboxClaim claim = claim(1);
        when(outboxService.claim(any(UUID.class), anyString())).thenReturn(Optional.of(claim));
        doThrow(new IllegalStateException("broker unavailable"))
                .when(notificationProducer)
                .publishConfirmed(any(NotificationInboundEvent.class));
        when(outboxService.markFailed(claim, IllegalStateException.class.getName()))
                .thenReturn(CollabNotificationOutboxService.FailureDisposition.RETRY_SCHEDULED);

        dispatcher.dispatch(claim.eventId());

        verify(outboxService).markFailed(claim, IllegalStateException.class.getName());
        verify(outboxService, never()).markPublished(any());
    }

    private static CollabNotificationOutboxClaim claim(int attemptCount) {
        return new CollabNotificationOutboxClaim(
                UUID.randomUUID(),
                UUID.randomUUID(),
                NotificationType.COLLAB_APPLICATION_RECEIVED,
                "Yeni basvuru",
                "Ilanina yeni bir basvuru geldi.",
                Map.of("module", "COLLAB", "action", "APPLICATION_RECEIVED"),
                false,
                Instant.parse("2026-08-11T00:00:00Z"),
                attemptCount,
                "node:lease"
        );
    }
}
