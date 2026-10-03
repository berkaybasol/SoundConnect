package com.berkayb.soundconnect.modules.studio.reservation.outbox;

import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationProducer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.amqp.AmqpException;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class StudioReservationNotificationOutboxDispatcherTest {
    private final StudioReservationNotificationOutboxService outbox = mock(StudioReservationNotificationOutboxService.class);
    private final NotificationProducer producer = mock(NotificationProducer.class);
    private final StudioReservationNotificationOutboxDispatcher dispatcher =
            new StudioReservationNotificationOutboxDispatcher(outbox, producer);

    @ParameterizedTest
    @EnumSource(value = StudioReservationNotificationOutboxService.FailureDisposition.class,
            names = {"RETRY_SCHEDULED", "DEAD_LETTER", "LEASE_LOST"})
    void failedConfirmationCannotBeMarkedPublishedAndOperationalLogsDoNotExposeContent(
            StudioReservationNotificationOutboxService.FailureDisposition disposition, CapturedOutput output) {
        var claim = claim();
        when(outbox.claim(eq(claim.eventId()), anyString())).thenReturn(Optional.of(claim));
        doThrow(new AmqpException("private-broker-error", new TimeoutException("private-timeout-cause")))
                .when(producer).publishConfirmed(claim.toInboundEvent());
        when(outbox.markFailed(claim, AmqpException.class.getName())).thenReturn(disposition);

        dispatcher.dispatch(claim.eventId());

        verify(outbox).markFailed(claim, AmqpException.class.getName());
        verify(outbox, never()).markPublished(any());
        assertThat(output.getAll()).contains("AmqpException").doesNotContain("private-title", "private-message",
                "private-room", "private-broker-error", "private-timeout-cause", claim.recipientId().toString());
        assertThat(claim.toString()).doesNotContain("private-title", "private-message", "private-room");
    }

    @Test void confirmedPublishPrecedesThePublishedDatabaseMark() {
        var claim = claim();
        when(outbox.claim(eq(claim.eventId()), anyString())).thenReturn(Optional.of(claim));
        when(outbox.markPublished(claim)).thenReturn(true);
        dispatcher.dispatch(claim.eventId());
        var order = inOrder(producer, outbox);
        order.verify(outbox).claim(eq(claim.eventId()), anyString());
        order.verify(producer).publishConfirmed(claim.toInboundEvent());
        order.verify(outbox).markPublished(claim);
        verify(outbox, never()).markFailed(any(), any());
        verify(producer, never()).publish(any());
    }

    @Test void databaseFailureAfterConfirmationRemainsARecoverableLease() {
        var claim = claim();
        when(outbox.claim(eq(claim.eventId()), anyString())).thenReturn(Optional.of(claim));
        when(outbox.markPublished(claim)).thenThrow(new IllegalStateException("fixture database unavailable"));
        assertThatThrownBy(() -> dispatcher.dispatch(claim.eventId())).isInstanceOf(IllegalStateException.class);
        verify(producer).publishConfirmed(claim.toInboundEvent());
        // A confirmed send must not be recorded as a broker failure: the existing
        // lease and stable identity are the recovery path after the process restarts.
        verify(outbox, never()).markFailed(any(), any());
    }

    @Test void losingTheDatabaseClaimCannotSendTheNotification() {
        UUID eventId = UUID.randomUUID();
        when(outbox.claim(eq(eventId), anyString())).thenReturn(Optional.empty());
        dispatcher.dispatch(eventId);
        verifyNoInteractions(producer);
        verify(outbox, never()).markPublished(any());
        verify(outbox, never()).markFailed(any(), any());
    }

    private static StudioReservationNotificationOutboxClaim claim() {
        return new StudioReservationNotificationOutboxClaim(UUID.randomUUID(), UUID.randomUUID(),
                NotificationType.STUDIO_RESERVATION_CREATED, "private-title", "private-message",
                Map.of("module", "STUDIO", "action", "CREATED", "reservationId", UUID.randomUUID().toString(),
                        "roomName", "private-room"),
                false, Instant.parse("2026-09-24T09:00:00Z"), 2, "fixture-owner");
    }
}
