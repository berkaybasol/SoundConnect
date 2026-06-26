package com.berkayb.soundconnect.modules.message.dm.event;

import com.berkayb.soundconnect.modules.message.dm.dto.response.DMMessageResponseDto;
import com.berkayb.soundconnect.modules.message.dm.helper.DmBadgeCacheHelper;
import com.berkayb.soundconnect.modules.message.dm.mapper.DMMessageMapper;
import com.berkayb.soundconnect.modules.message.dm.repository.DMMessageRepository;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.profile.shared.resolver.dto.UserProfileTargetDto;
import com.berkayb.soundconnect.modules.profile.shared.resolver.service.PublicProfileResolverService;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationInboundEvent;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationProducer;
import com.berkayb.soundconnect.shared.realtime.WebSocketChannels;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

/**
 * Dm mesaj event'lerini dinleyip, WebSocket/STOMP uzerinden anlik push yapan subscriber.
 * Sadece push ve badge isi yapar business loggic icermez
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DmMessageEventListener {
	private final SimpMessagingTemplate messagingTemplate;
	private final DMMessageMapper messageMapper;
	private final DMMessageRepository messageRepository;
	private final DmBadgeCacheHelper badgeCacheHelper;
	private final NotificationProducer notificationProducer;
	private final UserRepository userRepository;
	private final PublicProfileResolverService publicProfileResolverService;
	
	@EventListener
	public void onDmMessaggeSent (DmMessageSentEvent event) {
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
			log.error("DM mesajı WS push FAILED! event={}, err={}", event, e.toString());
		}
		long unread = messageRepository.findByConversationIdAndRecipientIdAndReadAtIsNull(
				event.getConversationId(), event.getRecipientId()
		).size(); // veya tek query ile recipient'in tum okunmamis DM'lerini sayabiliriz.
		
		badgeCacheHelper.setUnread(event.getRecipientId(), unread);
		
		// WS ile badge push
		Long cacheUnread = badgeCacheHelper.getCacheUnread(event.getRecipientId());
		String badgeDestination = WebSocketChannels.dmBadge(event.getRecipientId());
		messagingTemplate.convertAndSend(badgeDestination, cacheUnread != null ? cacheUnread : 0L);
		log.debug("DM unread badge WS push: userId={}, badge={}", event.getRecipientId(), cacheUnread);

		publishNotification(event);
	}

	private void publishNotification(DmMessageSentEvent event) {
		try {
			UserProfileTargetDto senderProfile = resolvePreferredSenderProfile(event);
			String senderUsername = resolveSenderUsername(event, senderProfile);
			String senderAvatarUrl = resolveSenderAvatarUrl(event, senderProfile);
			notificationProducer.publish(
					NotificationInboundEvent.builder()
					                        .recipientId(event.getRecipientId())
					                        .type(NotificationType.DM_NEW_MESSAGE)
					                        .title(senderUsername + " size bir mesaj gönderdi")
					                        .message(messagePreview(event))
					                        .payload(Map.of(
							                        "module", "DM",
							                        "conversationId", event.getConversationId().toString(),
							                        "messageId", event.getMessageId().toString(),
							                        "senderId", event.getSenderId().toString(),
							                        "senderUsername", senderUsername,
							                        "senderAvatarUrl", senderAvatarUrl,
							                        "recipientId", event.getRecipientId().toString(),
							                        "messageType", event.getMessageType() == null ? "text" : event.getMessageType()
					                        ))
					                        .emailForce(false)
					                        .occurredAt(Instant.now())
					                        .build()
			);
		} catch (Exception e) {
			log.warn("DM notification publish failed. messageId={}, recipientId={}, err={}",
			         event.getMessageId(), event.getRecipientId(), e.toString());
		}
	}

	private String resolveSenderUsername(DmMessageSentEvent event, UserProfileTargetDto profile) {
		if (profile != null && hasText(profile.displayName())) {
			return profile.displayName().trim();
		}
		return userRepository.findById(event.getSenderId())
		                     .map(user -> {
			                     String username = user.getUsername();
			                     return username == null || username.isBlank() ? "Bir kullanici" : username.trim();
		                     })
		                     .orElse("Bir kullanici");
	}

	private String resolveSenderAvatarUrl(DmMessageSentEvent event, UserProfileTargetDto profile) {
		if (profile != null && hasText(profile.profilePictureUrl())) {
			return profile.profilePictureUrl().trim();
		}
		return resolveUserProfilePicture(event);
	}

	private UserProfileTargetDto resolvePreferredSenderProfile(DmMessageSentEvent event) {
		try {
			var profiles = publicProfileResolverService.resolveByUserId(event.getSenderId()).profiles();
			if (profiles == null || profiles.isEmpty()) {
				return null;
			}
			return profiles.stream()
			               .filter(profile -> "VENUE".equalsIgnoreCase(profile.type()))
			               .findFirst()
			               .orElseGet(() -> profiles.stream().findFirst().orElse(null));
		} catch (Exception e) {
			log.warn("DM notification sender profile resolve failed. senderId={}, err={}",
			         event.getSenderId(), e.toString());
			return null;
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
}
