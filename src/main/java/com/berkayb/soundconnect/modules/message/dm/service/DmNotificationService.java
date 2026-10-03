package com.berkayb.soundconnect.modules.message.dm.service;

import com.berkayb.soundconnect.modules.message.dm.event.DmMessageSentEvent;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.notification.service.TransactionalNotificationService;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import com.berkayb.soundconnect.modules.profile.shared.resolver.dto.UserProfileTargetDto;
import com.berkayb.soundconnect.modules.profile.shared.resolver.service.PublicProfileResolverService;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationInboundEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Creates the inbox in the message transaction; delivery projections run after commit. */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(propagation = Propagation.MANDATORY)
public class DmNotificationService {
    private static final String UNKNOWN_SENDER = "Bir kullanici";
    private static final String MESSAGE_TITLE_SUFFIX = " size bir mesaj gönderdi";
    private final TransactionalNotificationService notifications;
    private final UserRepository userRepository;
    private final PublicProfileResolverService publicProfileResolverService;

    public static UUID eventId(UUID messageId, UUID recipientId) {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(recipientId, "recipientId");
        return UUID.nameUUIDFromBytes(("DM_NEW_MESSAGE:" + messageId + ":" + recipientId)
                .getBytes(StandardCharsets.UTF_8));
    }
	public void persist(DmMessageSentEvent event) {
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
		notifications.persistInCurrentTransaction(
				NotificationInboundEvent.builder()
				                        .eventId(eventId(event.getMessageId(), event.getRecipientId()))
				                        .recipientId(event.getRecipientId())
				                        .type(NotificationType.DM_NEW_MESSAGE)
				                        .title(truncate(senderUsername, 160 - MESSAGE_TITLE_SUFFIX.length()) + MESSAGE_TITLE_SUFFIX)
				                        .message(messagePreview(event))
				                        .payload(payload)
				                        .emailForce(false)
				                        .occurredAt(Instant.now())
				                        .build()
		);
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
		return content.length() > 120 ? truncate(content, 120) + "..." : content;
	}

	private static String truncate(String text, int limit) {
		if (text.length() <= limit) return text;
		int end = limit;
		if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
		return text.substring(0, end);
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
