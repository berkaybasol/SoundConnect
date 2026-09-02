package com.berkayb.soundconnect.modules.tablegroup.notification.outbox;

import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
@RequiredArgsConstructor
@Slf4j
public class TableGroupNotificationOutboxDispatcher {
	private final TableGroupNotificationOutboxService outboxService;
	private final NotificationProducer notificationProducer;
	private final String nodeId = UUID.randomUUID().toString();

	public void dispatch(UUID eventId) {
		String leaseOwner = nodeId + ":" + UUID.randomUUID();
		Optional<TableGroupNotificationOutboxClaim> optionalClaim = outboxService.claim(eventId, leaseOwner);
		if (optionalClaim.isEmpty()) return;

		TableGroupNotificationOutboxClaim claim = optionalClaim.get();
		try {
			notificationProducer.publishConfirmed(claim.toInboundEvent());
		} catch (Exception exception) {
			var disposition = outboxService.markFailed(claim, exception.getClass().getName());
			if (disposition == TableGroupNotificationOutboxService.FailureDisposition.DEAD_LETTER) {
				log.error("TableGroup notification reached DEAD_LETTER. eventId={}, type={}, attempts={}, exceptionType={}",
						claim.eventId(), claim.type(), claim.attemptCount(), exception.getClass().getName());
			} else {
				log.warn("TableGroup notification publish failed. eventId={}, type={}, attempts={}, disposition={}, exceptionType={}",
						claim.eventId(), claim.type(), claim.attemptCount(), disposition,
						exception.getClass().getName());
			}
			return;
		}

		if (!outboxService.markPublished(claim)) {
			log.warn("TableGroup notification ACK could not be fenced; retry will reuse eventId. eventId={}, type={}",
					claim.eventId(), claim.type());
		}
	}
}
