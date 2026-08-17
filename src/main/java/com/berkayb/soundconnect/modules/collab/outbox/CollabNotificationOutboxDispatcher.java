package com.berkayb.soundconnect.modules.collab.outbox;

import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class CollabNotificationOutboxDispatcher {
    private final CollabNotificationOutboxService outboxService;
    private final NotificationProducer notificationProducer;
    private final String nodeId = UUID.randomUUID().toString();

    public void dispatch(UUID eventId) {
        String leaseOwner = nodeId + ":" + UUID.randomUUID();
        Optional<CollabNotificationOutboxClaim> optionalClaim = outboxService.claim(eventId, leaseOwner);
        if (optionalClaim.isEmpty()) {
            return;
        }

        CollabNotificationOutboxClaim claim = optionalClaim.get();
        try {
            notificationProducer.publishConfirmed(claim.toInboundEvent());
        } catch (Exception exception) {
            CollabNotificationOutboxService.FailureDisposition disposition = outboxService.markFailed(
                    claim,
                    exception.getClass().getName()
            );
            if (disposition == CollabNotificationOutboxService.FailureDisposition.DEAD_LETTER) {
                log.error(
                        "Collab notification outbox reached DEAD_LETTER. eventId={}, type={}, attempts={}, exceptionType={}",
                        claim.eventId(), claim.type(), claim.attemptCount(), exception.getClass().getName()
                );
            } else {
                log.warn(
                        "Collab notification outbox publish failed. eventId={}, type={}, attempts={}, disposition={}, exceptionType={}",
                        claim.eventId(), claim.type(), claim.attemptCount(), disposition,
                        exception.getClass().getName()
                );
            }
            return;
        }

        if (outboxService.markPublished(claim)) {
            log.debug(
                    "Collab notification outbox published. eventId={}, type={}, attempts={}",
                    claim.eventId(), claim.type(), claim.attemptCount()
            );
        } else {
            // An ACK followed by a database failure is intentionally retried with
            // the same eventId. The consumer's unique source_event_id makes that safe.
            log.warn(
                    "Collab notification outbox ACK could not be fenced as published; lease recovery will retry. eventId={}, type={}",
                    claim.eventId(), claim.type()
            );
        }
    }
}
