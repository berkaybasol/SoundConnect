package com.berkayb.soundconnect.modules.message.dm.event;

import com.berkayb.soundconnect.modules.message.dm.dto.response.DMMessageResponseDto;
import com.berkayb.soundconnect.modules.message.dm.mapper.DMMessageMapper;
import com.berkayb.soundconnect.modules.message.dm.repository.DMMessageRepository;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import com.berkayb.soundconnect.modules.profile.shared.resolver.dto.UserProfileTargetDto;
import com.berkayb.soundconnect.modules.profile.shared.resolver.service.PublicProfileResolverService;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationInboundEvent;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationProducer;
import com.berkayb.soundconnect.shared.realtime.WebSocketChannels;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Dm mesaj event'lerini dinleyip, WebSocket/STOMP uzerinden anlik push yapan subscriber.
 * Sadece push ve badge isi yapar business loggic icermez
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DmMessageEventListener {
	private static final String UNKNOWN_SENDER = "Bir kullanici";

	private final SimpMessagingTemplate messagingTemplate;
	private final DMMessageMapper messageMapper;
	private final DMMessageRepository messageRepository;
	private final NotificationProducer notificationProducer;
	private final UserRepository userRepository;
	private final PublicProfileResolverService publicProfileResolverService;
	
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void onDmMessageSent(DmMessageSentEvent event) {
		try {
			// eventteki bilgiden DMMessage entity'sini DB'den cek (responseDto icin)
			var msg = messageRepository.findById(event.getMessageId())
					.orElse(null);
			if (msg == null) {
				log.warn("WS DM push: Message not found! messageId={}", event.getMessageId());
				return;
			}
			DMMessageResponseDto dto = messageMapper.toResponseDto(msg);
			
			// Recipient'e anlik mesaj push (dm kanalina)
			String recipientDestination = WebSocketChannels.dm(event.getRecipientId());
			messagingTemplate.convertAndSend(recipientDestination, dto);
			log.debug("DM mesajı WS push: senderId={}, dest={}", event.getSenderId(), recipientDestination);
			
			// Sender'a anlik push
			String senderDestination = WebSocketChannels.dm(event.getSenderId());
			messagingTemplate.convertAndSend(senderDestination, dto);
			log.debug("DM mesajı WS push: senderId={}, dest={}", event.getSenderId(), senderDestination);
		} catch (Exception e) {
			log.error("DM message WS push failed messageId={} conversationId={} exceptionType={}",
			          event.getMessageId(), event.getConversationId(), e.getClass().getSimpleName());
		}
		refreshUnreadBadge(event.getRecipientId());

		publishNotification(event);
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onDmMessageRead(DmMessageReadEvent event) {
		refreshUnreadBadge(event.readerId());
	}

	private void refreshUnreadBadge(UUID userId) {
		try {
			long unread = messageRepository.countByRecipientIdAndReadAtIsNull(userId);
			String badgeDestination = WebSocketChannels.dmBadge(userId);
			messagingTemplate.convertAndSend(badgeDestination, unread);
			log.debug("DM unread badge WS push: userId={}, badge={}", userId, unread);
		} catch (Exception exception) {
			log.warn("DM unread badge refresh failed userId={} exceptionType={}",
			         userId, exception.getClass().getSimpleName());
		}
	}

	private void publishNotification(DmMessageSentEvent event) {
		try {
			SenderProfileResolution resolution = resolvePreferredSenderProfile(event);
			UserProfileTargetDto senderProfile = resolution.profile();
			String senderUsername = resolution.failed()
					? UNKNOWN_SENDER
					: resolveSenderUsername(event, senderProfile);
			String senderAvatarUrl = resolution.failed()
					? ""
					: resolveSenderAvatarUrl(event, senderProfile);
			Map<String, Object> payload = new LinkedHashMap<>();
			payload.put("module", "DM");
			payload.put("conversationId", event.getConversationId().toString());
			payload.put("messageId", event.getMessageId().toString());
			payload.put("senderId", event.getSenderId().toString());
			payload.put("senderUsername", senderUsername);
			payload.put("senderAvatarUrl", senderAvatarUrl);
			payload.put("recipientId", event.getRecipientId().toString());
			payload.put("messageType", event.getMessageType() == null ? "text" : event.getMessageType());
			if (senderProfile != null
					&& senderProfile.visibilityMode() == ListenerVisibilityMode.GHOST) {
				payload.put("senderVisibilityMode", ListenerVisibilityMode.GHOST.name());
			}
			// This notification stores a creation-time identity snapshot. Consumers
			// opening it later must resolve the sender again because ghost mode can
			// be toggled after the message was published.
			notificationProducer.publish(
					NotificationInboundEvent.builder()
					                        .recipientId(event.getRecipientId())
					                        .type(NotificationType.DM_NEW_MESSAGE)
					                        .title(senderUsername + " size bir mesaj gönderdi")
					                        .message(messagePreview(event))
					                        .payload(payload)
					                        .emailForce(false)
					                        .occurredAt(Instant.now())
					                        .build()
			);
		} catch (Exception e) {
			log.warn("DM notification publish failed. messageId={}, recipientId={}, exceptionType={}",
			         event.getMessageId(), event.getRecipientId(), e.getClass().getSimpleName());
		}
	}

	private String resolveSenderUsername(DmMessageSentEvent event, UserProfileTargetDto profile) {
		if (profile != null && hasText(profile.displayName())) {
			return profile.displayName().trim();
		}
		return userRepository.findById(event.getSenderId())
		                     .map(user -> {
			                     String username = user.getUsername();
			                     return username == null || username.isBlank() ? UNKNOWN_SENDER : username.trim();
		                     })
		                     .orElse(UNKNOWN_SENDER);
	}

	private String resolveSenderAvatarUrl(DmMessageSentEvent event, UserProfileTargetDto profile) {
		if (profile != null && hasText(profile.profilePictureUrl())) {
			return profile.profilePictureUrl().trim();
		}
		if (profile != null && profile.visibilityMode() == ListenerVisibilityMode.GHOST) {
			// Never substitute a user-level or alternate-profile image for a ghost.
			return "";
		}
		return resolveUserProfilePicture(event);
	}

	private SenderProfileResolution resolvePreferredSenderProfile(DmMessageSentEvent event) {
		try {
			var response = publicProfileResolverService.resolveByUserId(event.getSenderId());
			if (response == null) {
				throw new IllegalStateException("Public profile resolver returned null");
			}
			var profiles = response.profiles();
			if (profiles == null || profiles.isEmpty()) {
				return SenderProfileResolution.resolved(null);
			}
			UserProfileTargetDto selectedProfile = profiles.stream()
			               .filter(candidate -> "VENUE".equalsIgnoreCase(candidate.type()))
			               .findFirst()
			               .orElseGet(() -> profiles.stream().findFirst().orElse(null));
			return SenderProfileResolution.resolved(selectedProfile);
		} catch (Exception e) {
			log.warn("DM notification sender profile resolve failed. senderId={}, exceptionType={}",
			         event.getSenderId(), e.getClass().getSimpleName());
			return SenderProfileResolution.failure();
		}
	}

	private String resolveUserProfilePicture(DmMessageSentEvent event) {
		return userRepository.findById(event.getSenderId())
		                     .map(user -> {
			                     String profilePicture = user.getProfilePicture();
			                     return profilePicture == null ? "" : profilePicture.trim();
		                     })
		                     .orElse("");
	}

	private String messagePreview(DmMessageSentEvent event) {
		String type = event.getMessageType() == null ? "text" : event.getMessageType().trim();
		if (!type.equalsIgnoreCase("text")) {
			return "Yeni bir " + type + " mesaji aldin.";
		}
		String content = event.getContent() == null ? "" : event.getContent().trim();
		if (content.isEmpty()) {
			return "Yeni bir mesaj aldin.";
		}
		return content.length() > 120 ? content.substring(0, 120) + "..." : content;
	}

	private boolean hasText(String value) {
		return value != null && !value.trim().isEmpty();
	}

	private record SenderProfileResolution(UserProfileTargetDto profile, boolean failed) {
		private static SenderProfileResolution resolved(UserProfileTargetDto profile) {
			return new SenderProfileResolution(profile, false);
		}

		private static SenderProfileResolution failure() {
			return new SenderProfileResolution(null, true);
		}
	}
}
