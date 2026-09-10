package com.berkayb.soundconnect.modules.overthinking.outbox;

import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class OverthinkingNotificationOutboxDispatcher {
	private final OverthinkingNotificationOutboxService outboxService;
	private final NotificationProducer notificationProducer;
	private final String nodeId = UUID.randomUUID().toString();

	public void dispatch(UUID eventId) {
		String leaseOwner = nodeId + ":" + UUID.randomUUID();
		Optional<OverthinkingNotificationOutboxClaim> optionalClaim =
				outboxService.claim(eventId, leaseOwner);
		if (optionalClaim.isEmpty()) {
			return;
		}

		OverthinkingNotificationOutboxClaim claim = optionalClaim.get();
		try {
			notificationProducer.publishConfirmed(claim.toInboundEvent());
		} catch (Exception exception) {
			OverthinkingNotificationOutboxService.FailureDisposition disposition =
					outboxService.markFailed(claim, exception.getClass().getName());
			if (disposition == OverthinkingNotificationOutboxService.FailureDisposition.DEAD_LETTER) {
				log.error(
						"Overthinking notification outbox reached DEAD_LETTER. eventId={}, type={}, attempts={}, exceptionType={}",
						claim.eventId(), claim.type(), claim.attemptCount(), exception.getClass().getName()
				);
			} else {
				log.warn(
						"Overthinking notification outbox publish failed. eventId={}, type={}, attempts={}, disposition={}, exceptionType={}",
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
					"Overthinking notification outbox ACK could not be fenced; lease recovery will retry. eventId={}, type={}",
					claim.eventId(), claim.type()
			);
		}
	}
}
