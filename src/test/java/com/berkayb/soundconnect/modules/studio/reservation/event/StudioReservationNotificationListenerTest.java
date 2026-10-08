package com.berkayb.soundconnect.modules.studio.reservation.event;

import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.studio.reservation.outbox.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class StudioReservationNotificationListenerTest {
    private final StudioReservationNotificationOutboxService outbox = mock(StudioReservationNotificationOutboxService.class);
    private final StudioReservationNotificationOutboxDispatcher dispatcher = mock(StudioReservationNotificationOutboxDispatcher.class);
    private final StudioReservationNotificationDispatchCoordinator coordinator = mock(StudioReservationNotificationDispatchCoordinator.class);
    private final StudioReservationNotificationListener listener =
            new StudioReservationNotificationListener(outbox, dispatcher, coordinator);

    @Test void durabilityParticipatesInCommitAndDeliveryWaitsForCommit() throws Exception {
        var persist = annotation("persistInDomainTransaction");
        var deliver = annotation("onReservationNotification");
        assertThat(persist.phase()).isEqualTo(TransactionPhase.BEFORE_COMMIT);
        assertThat(deliver.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
        assertThat(persist.fallbackExecution()).isFalse();
        assertThat(deliver.fallbackExecution()).isFalse();
    }

    @Test void enqueueFailurePropagatesSoTheDomainTransactionCanRollBack() {
        var event = event();
        var failure = new IllegalStateException("fixture storage unavailable");
        doThrow(failure).when(outbox).enqueue(event);
        assertThatThrownBy(() -> listener.persistInDomainTransaction(event)).isSameAs(failure);
        verifyNoInteractions(coordinator, dispatcher);
    }

    @Test void deliveryOnlyRunsInsideAcceptedWorker() {
        var event = event();
        listener.onReservationNotification(event);
        var task = org.mockito.ArgumentCaptor.forClass(Runnable.class);
        verify(coordinator).trySchedule(eq(event.eventId()), task.capture());
        verifyNoInteractions(dispatcher);
        task.getValue().run();
        verify(dispatcher).dispatch(event.eventId());
        verifyNoInteractions(outbox);
    }

    @Test void rejectedExecutorLeavesDurableRecoveryWithoutLoggingBody(CapturedOutput output) {
        var event = event();
        when(coordinator.trySchedule(eq(event.eventId()), any())).thenThrow(new RejectedExecutionException("private-fixture-detail"));
        assertThatCode(() -> listener.onReservationNotification(event)).doesNotThrowAnyException();
        verifyNoInteractions(dispatcher, outbox);
        assertThat(output.getOut()).contains("RejectedExecutionException")
                .doesNotContain("private-fixture-detail", event.title(), event.message(), "private-room", event.recipientId().toString());
    }

    @Test void workerFailureIsRedactedAndDoesNotUndoCommit(CapturedOutput output) {
        var event = event();
        doThrow(new IllegalStateException("private-worker-detail")).when(dispatcher).dispatch(event.eventId());
        when(coordinator.trySchedule(eq(event.eventId()), any())).thenAnswer(call -> {
            ((Runnable) call.getArgument(1)).run();
            return true;
        });
        assertThatCode(() -> listener.onReservationNotification(event)).doesNotThrowAnyException();
        assertThat(output.getOut()).contains("IllegalStateException")
                .doesNotContain("private-worker-detail", event.title(), event.message(), "private-room");
    }

    private static TransactionalEventListener annotation(String method) throws Exception {
        return StudioReservationNotificationListener.class.getMethod(method, StudioReservationNotificationEvent.class)
                .getAnnotation(TransactionalEventListener.class);
    }
    private static StudioReservationNotificationEvent event() {
        return new StudioReservationNotificationEvent(UUID.randomUUID(), NotificationType.STUDIO_RESERVATION_CREATED,
                "private-title", "private-body", Map.of("module", "STUDIO", "action", "CREATED",
                "reservationId", UUID.randomUUID().toString(), "roomName", "private-room"), Instant.parse("2026-09-24T09:00:00Z"));
    }
}
