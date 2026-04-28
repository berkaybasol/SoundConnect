package com.berkayb.soundconnect.modules.overthinking.service;

import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.overthinking.entity.OverthinkingRevealRequest;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationInboundEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class OverthinkingNotificationServiceImpl implements OverthinkingNotificationService {
	
	private static final String OVERTHINKING_NOTIFICATION_ROUTING_KEY = "notification.overthinking";
	
	private final RabbitTemplate rabbitTemplate;
	
	@Value("${app.messaging.notification.exchange:notification.exchange}")
	private String notificationExchange;
	
	@Override
	public void sendRevealRequestReceivedNotification(OverthinkingRevealRequest request) {
		Map<String, Object> payload = basePayload(request);
		payload.put("requesterId", request.getRequester().getId().toString());
		
		publish(
				request.getAuthor().getId(),
				NotificationType.OVERTHINKING_REVEAL_REQUEST_RECEIVED,
				"Anonim paylaşımına görüntüleme isteği geldi",
				request.getRequester().getUsername() + " profilini görüntülemek istiyor.",
				payload,
				request
		);
	}
	
	@Override
	public void sendRevealRequestApprovedNotification(OverthinkingRevealRequest request) {
		Map<String, Object> payload = basePayload(request);
		payload.put("authorId", request.getAuthor().getId().toString());
		
		publish(
				request.getRequester().getId(),
				NotificationType.OVERTHINKING_REVEAL_REQUEST_APPROVED,
				"Profil görüntüleme isteğin kabul edildi",
				"Artık anonim paylaşımın sahibini görüntüleyebilirsin.",
				payload,
				request
		);
	}
	
	@Override
	public void sendRevealRequestRejectedNotification(OverthinkingRevealRequest request) {
		Map<String, Object> payload = basePayload(request);
		
		publish(
				request.getRequester().getId(),
				NotificationType.OVERTHINKING_REVEAL_REQUEST_REJECTED,
				"Profil görüntüleme isteğin reddedildi",
				"Yazar şu an profilini paylaşmak istemiyor.",
				payload,
				request
		);
	}
	
	private Map<String, Object> basePayload(OverthinkingRevealRequest request) {
		Map<String, Object> payload = new HashMap<>();
		payload.put("module", "OVERTHINKING");
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
			Map<String, Object> payload,
			OverthinkingRevealRequest request
	) {
		NotificationInboundEvent event = NotificationInboundEvent.builder()
		                                                         .recipientId(recipientId)
		                                                         .type(type)
		                                                         .title(title)
		                                                         .message(message)
		                                                         .payload(payload)
		                                                         .emailForce(false)
		                                                         .occurredAt(Instant.now())
		                                                         .build();
		
		try {
			rabbitTemplate.convertAndSend(
					notificationExchange,
					OVERTHINKING_NOTIFICATION_ROUTING_KEY,
					event
			);
			
			log.info(
					"[OverthinkingNotification] Notification published. type={}, recipient={}, request={}",
					type,
					recipientId,
					request.getId()
			);
		} catch (Exception e) {
			log.warn(
					"[OverthinkingNotification] Notification publish failed. type={}, recipient={}, request={}, err={}",
					type,
					recipientId,
					request.getId(),
					e.toString()
			);
		}
	}
}