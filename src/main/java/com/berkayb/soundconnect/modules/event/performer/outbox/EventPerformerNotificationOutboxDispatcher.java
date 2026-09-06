package com.berkayb.soundconnect.modules.event.performer.outbox;

import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class EventPerformerNotificationOutboxDispatcher {
	private final EventPerformerNotificationOutboxService outboxService;
	private final NotificationProducer notificationProducer;
	private final String nodeId = UUID.randomUUID().toString();

	public void dispatch(UUID eventId) {
		String leaseOwner = nodeId + ":" + UUID.randomUUID();
		Optional<EventPerformerNotificationOutboxClaim> optionalClaim =
				outboxService.claim(eventId, leaseOwner);
		if (optionalClaim.isEmpty()) {
			return;
		}

		EventPerformerNotificationOutboxClaim claim = optionalClaim.get();
		try {
			notificationProducer.publishConfirmed(claim.toInboundEvent());
		} catch (Exception exception) {
			EventPerformerNotificationOutboxService.FailureDisposition disposition =
					outboxService.markFailed(claim, exception.getClass().getName());
			if (disposition == EventPerformerNotificationOutboxService.FailureDisposition.DEAD_LETTER) {
				log.error(
						"Event performer notification outbox reached DEAD_LETTER. eventId={}, type={}, attempts={}, exceptionType={}",
						claim.eventId(), claim.type(), claim.attemptCount(), exception.getClass().getName()
				);
			} else {
				log.warn(
						"Event performer notification outbox publish failed. eventId={}, type={}, attempts={}, disposition={}, exceptionType={}",
						claim.eventId(), claim.type(), claim.attemptCount(), disposition,
						exception.getClass().getName()
				);
			}
			return;
		}

		if (!outboxService.markPublished(claim)) {
			// A confirmed delivery followed by a failed status fence is retried with
			// the stable event id; the notification consumer deduplicates it.
			log.warn(
					"Event performer notification outbox ACK could not be fenced; lease recovery will retry. eventId={}, type={}",
					claim.eventId(), claim.type()
			);
		}
	}
}
