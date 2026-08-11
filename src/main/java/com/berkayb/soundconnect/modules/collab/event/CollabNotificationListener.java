package com.berkayb.soundconnect.modules.collab.event;

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
public class CollabNotificationListener {
    private final NotificationProducer notificationProducer;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCollabNotification(CollabNotificationEvent event) {
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
            // Recipient and human-readable content are intentionally excluded
            // from logs because they can contain personal data.
            log.error(
                    "Collab notification publish failed. type={}, exceptionType={}",
                    event.type(),
                    exception.getClass().getName()
            );
        }
    }
}
