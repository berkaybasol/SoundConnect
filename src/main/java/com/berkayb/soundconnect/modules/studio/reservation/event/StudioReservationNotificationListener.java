package com.berkayb.soundconnect.modules.studio.reservation.event;

import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationInboundEvent;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
@Slf4j
public class StudioReservationNotificationListener {
    private final NotificationProducer notificationProducer;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReservationNotification(StudioReservationNotificationEvent event) {
        try {
            notificationProducer.publish(
                    NotificationInboundEvent.builder()
                            .recipientId(event.recipientId())
                            .type(event.type())
                            .title(event.title())
                            .message(event.message())
                            .payload(event.payload())
                            .emailForce(false)
                            .occurredAt(event.occurredAt())
                            .build()
            );
        } catch (Exception exception) {
            log.warn(
                    "Studio reservation notification publish failed. recipientId={}, type={}, exceptionType={}",
                    event.recipientId(),
                    event.type(),
                    exception.getClass().getSimpleName()
            );
        }
    }
}
