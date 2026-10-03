package com.berkayb.soundconnect.modules.studio.reservation.event;

import com.berkayb.soundconnect.modules.studio.reservation.outbox.StudioReservationNotificationDispatchCoordinator;
import com.berkayb.soundconnect.modules.studio.reservation.outbox.StudioReservationNotificationOutboxDispatcher;
import com.berkayb.soundconnect.modules.studio.reservation.outbox.StudioReservationNotificationOutboxService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@Slf4j
public class StudioReservationNotificationListener {
    private final StudioReservationNotificationOutboxService outboxService;
    private final StudioReservationNotificationOutboxDispatcher dispatcher;
    private final StudioReservationNotificationDispatchCoordinator dispatchCoordinator;

    public StudioReservationNotificationListener(
            StudioReservationNotificationOutboxService outboxService,
            StudioReservationNotificationOutboxDispatcher dispatcher,
            StudioReservationNotificationDispatchCoordinator dispatchCoordinator
    ) {
        this.outboxService = outboxService;
        this.dispatcher = dispatcher;
        this.dispatchCoordinator = dispatchCoordinator;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void persistInDomainTransaction(StudioReservationNotificationEvent event) {
        outboxService.enqueue(event);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReservationNotification(StudioReservationNotificationEvent event) {
        try {
            dispatchCoordinator.trySchedule(event.eventId(), () -> {
                try {
                    dispatcher.dispatch(event.eventId());
                } catch (RuntimeException exception) {
                    log.warn(
                            "Studio reservation notification immediate dispatch task failed; durable row remains recoverable. eventId={}, type={}, exceptionType={}",
                            event.eventId(), event.type(), exception.getClass().getName()
                    );
                }
            });
        } catch (RuntimeException exception) {
            // The committed PENDING row is the fallback. Do not expose recipient,
            // title, message or payload in operational logs.
            log.warn(
                    "Studio reservation notification immediate dispatch failed; scheduler will retry. eventId={}, type={}, exceptionType={}",
                    event.eventId(), event.type(), exception.getClass().getName()
            );
        }
    }
}
