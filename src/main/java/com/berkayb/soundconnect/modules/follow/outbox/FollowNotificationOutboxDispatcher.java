package com.berkayb.soundconnect.modules.follow.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class FollowNotificationOutboxDispatcher {
    private final FollowNotificationOutboxService outboxService;
    private final FollowNotificationPublisher publisher;
    private final String nodeId = UUID.randomUUID().toString();

    public void dispatch(UUID eventId) {
        String leaseOwner = nodeId + ":" + UUID.randomUUID();
        Optional<FollowNotificationOutboxClaim> optionalClaim = outboxService.claim(eventId, leaseOwner);
        if (optionalClaim.isEmpty()) {
            return;
        }

        FollowNotificationOutboxClaim claim = optionalClaim.get();
        try {
            if (!publisher.publish(claim)) {
                boolean fenced = outboxService.markSuppressed(claim);
                log.info("Follow notification source unavailable. eventId={}, suppressionRecorded={}", claim.eventId(), fenced);
                return;
            }
        } catch (Exception exception) {
            FollowNotificationOutboxService.FailureDisposition disposition = outboxService.markFailed(
                    claim,
                    exception.getClass().getName()
            );
            if (disposition == FollowNotificationOutboxService.FailureDisposition.DEAD_LETTER) {
                log.error(
                        "Follow notification outbox reached DEAD_LETTER. eventId={}, type={}, attempts={}, exceptionType={}",
                        claim.eventId(), claim.type(), claim.attemptCount(), exception.getClass().getName()
                );
            } else {
                log.warn(
                        "Follow notification outbox publish failed. eventId={}, type={}, attempts={}, disposition={}, exceptionType={}",
                        claim.eventId(), claim.type(), claim.attemptCount(), disposition,
                        exception.getClass().getName()
                );
            }
            return;
        }

        if (outboxService.markPublished(claim)) {
            log.debug(
                    "Follow notification outbox published. eventId={}, type={}, attempts={}",
                    claim.eventId(), claim.type(), claim.attemptCount()
            );
        } else {
            // An ACK followed by a database failure is intentionally retried with
            // the same eventId. The consumer's unique source_event_id makes that safe.
            log.warn(
                    "Follow notification outbox ACK could not be fenced as published; lease recovery will retry. eventId={}, type={}",
                    claim.eventId(), claim.type()
            );
        }
    }
}
