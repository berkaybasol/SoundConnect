package com.berkayb.soundconnect.modules.overthinking.service;

import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.overthinking.entity.OverthinkingRevealRequest;
import com.berkayb.soundconnect.modules.overthinking.outbox.OverthinkingNotificationOutboxPublisher;
import com.berkayb.soundconnect.modules.overthinking.outbox.OverthinkingNotificationOutboxTimeProvider;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationInboundEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class OverthinkingNotificationServiceImpl implements OverthinkingNotificationService {
	
	private final OverthinkingNotificationOutboxPublisher notificationOutboxPublisher;
	private final OverthinkingNotificationOutboxTimeProvider timeProvider;
	
	@Override
	public void sendRevealRequestReceivedNotification(OverthinkingRevealRequest request) {
		if (!request.isPending()) throw new IllegalArgumentException("Received notification requires a pending request");
		Map<String, Object> payload = basePayload(request, "REVEAL_REQUEST_RECEIVED");
		payload.put("requesterId", request.getRequester().getId().toString());
		
		publish(
				request.getAuthor().getId(),
				NotificationType.OVERTHINKING_REVEAL_REQUEST_RECEIVED,
				"Anonim paylaşımına görüntüleme isteği geldi",
				"Birisi bu yazıda profilini görüntülemek istiyor.",
				payload
		);
	}
	
	@Override
	public void sendRevealRequestApprovedNotification(OverthinkingRevealRequest request) {
		if (!request.isApproved()) throw new IllegalArgumentException("Approved notification requires an approved request");
		Map<String, Object> payload = basePayload(request, "REVEAL_REQUEST_APPROVED");
		payload.put("authorId", request.getAuthor().getId().toString());
		
		publish(
				request.getRequester().getId(),
				NotificationType.OVERTHINKING_REVEAL_REQUEST_APPROVED,
				"Profil görüntüleme isteğin kabul edildi",
				"Artık anonim paylaşımın sahibini görüntüleyebilirsin.",
				payload
		);
	}
	
	@Override
	public void sendRevealRequestRejectedNotification(OverthinkingRevealRequest request) {
		if (!request.isRejected()) throw new IllegalArgumentException("Rejected notification requires a rejected request");
		Map<String, Object> payload = basePayload(request, "REVEAL_REQUEST_REJECTED");
		
		publish(
				request.getRequester().getId(),
				NotificationType.OVERTHINKING_REVEAL_REQUEST_REJECTED,
				"Profil görüntüleme isteğin reddedildi",
				"Yazar şu an profilini paylaşmak istemiyor.",
				payload
		);
	}
	
	private Map<String, Object> basePayload(OverthinkingRevealRequest request, String action) {
		Map<String, Object> payload = new HashMap<>();
		payload.put("module", "OVERTHINKING");
		payload.put("action", action);
		payload.put("postId", request.getPost().getId().toString());
		payload.put("postTitle", request.getPost().getTitle());
		payload.put("revealRequestId", request.getId().toString());
		return payload;
	}
	
	private void publish(
			UUID recipientId,
			NotificationType type,
			String title,
			String message,
			Map<String, Object> payload
	) {
		NotificationInboundEvent event = NotificationInboundEvent.builder()
		                                                         .eventId(UUID.randomUUID())
		                                                         .recipientId(recipientId)
		                                                         .type(type)
		                                                         .title(title)
		                                                         .message(message)
		                                                         .payload(payload)
		                                                         .emailForce(false)
		                                                         .occurredAt(timeProvider.now())
		                                                         .build();
		
		// Enqueue failure must roll back the reveal transition. Broker delivery is
		// attempted only after commit and remains recoverable by the scheduler.
		notificationOutboxPublisher.enqueueAll(List.of(event));
	}
}
