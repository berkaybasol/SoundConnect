package com.berkayb.soundconnect.modules.collab.event;

import com.berkayb.soundconnect.modules.collab.outbox.CollabNotificationDispatchCoordinator;
import com.berkayb.soundconnect.modules.collab.outbox.CollabNotificationOutboxDispatcher;
import com.berkayb.soundconnect.modules.collab.outbox.CollabNotificationOutboxService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@Slf4j
public class CollabNotificationListener {
    private final CollabNotificationOutboxService outboxService;
    private final CollabNotificationOutboxDispatcher dispatcher;
    private final CollabNotificationDispatchCoordinator dispatchCoordinator;

    public CollabNotificationListener(
            CollabNotificationOutboxService outboxService,
            CollabNotificationOutboxDispatcher dispatcher,
            CollabNotificationDispatchCoordinator dispatchCoordinator
    ) {
        this.outboxService = outboxService;
        this.dispatcher = dispatcher;
        this.dispatchCoordinator = dispatchCoordinator;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void persistInDomainTransaction(CollabNotificationEvent event) {
        outboxService.enqueue(event);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCollabNotification(CollabNotificationEvent event) {
        try {
            dispatchCoordinator.trySchedule(event.eventId(), () -> {
                try {
                    dispatcher.dispatch(event.eventId());
                } catch (RuntimeException exception) {
                    log.warn(
                            "Collab notification immediate dispatch task failed; durable row remains recoverable. eventId={}, type={}, exceptionType={}",
                            event.eventId(), event.type(), exception.getClass().getName()
                    );
                }
            });
        } catch (RuntimeException exception) {
            // The committed PENDING row is the fallback. Do not expose recipient,
            // title, message or payload in operational logs.
            log.warn(
                    "Collab notification immediate dispatch failed; scheduler will retry. eventId={}, type={}, exceptionType={}",
                    event.eventId(), event.type(), exception.getClass().getName()
            );
        }
    }
}
