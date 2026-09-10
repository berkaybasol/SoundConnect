package com.berkayb.soundconnect.modules.notification.service;

import com.berkayb.soundconnect.modules.notification.dto.response.NotificationResponseDto;
import com.berkayb.soundconnect.modules.notification.entity.Notification;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.notification.helper.NotificationBadgeCacheHelper;
import com.berkayb.soundconnect.modules.notification.mapper.NotificationMapper;
import com.berkayb.soundconnect.modules.notification.repository.NotificationRepository;
import com.berkayb.soundconnect.modules.notification.repository.NotificationReceiptRepository;
import com.berkayb.soundconnect.modules.notification.websocket.NotificationWebSocketService;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import com.berkayb.soundconnect.modules.profile.shared.identity.GhostListenerIdentity;
import com.berkayb.soundconnect.modules.profile.shared.identity.GhostListenerIdentityBatchResolver;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationServiceImpl implements NotificationService {
	private static final int MAX_PAGE = 1000;
	private static final int MAX_PAGE_SIZE = 100;
	private static final String SAFE_DM_TITLE = "Yeni mesaj";
	private static final Sort NOTIFICATION_SORT = Sort.by(
			Sort.Order.desc("occurredAt"),
			Sort.Order.desc("id")
	);
	
	private final NotificationRepository notificationRepository;
	private final NotificationMapper notificationMapper;
	private final NotificationBadgeCacheHelper badgeCacheHelper;
	private final NotificationWebSocketService notificationWebSocketService;
	private final GhostListenerIdentityBatchResolver ghostListenerIdentityBatchResolver;
	private final NotificationReceiptRepository receiptRepository;

	@Override
	@Transactional(propagation = Propagation.REQUIRED)
	public NotificationResponseDto refreshActorIdentityForDelivery(NotificationResponseDto notification) {
		// Delivery runs after the inbox transaction commits. Resolve current
		// privacy in the delivery worker's fresh transaction and keep its locks
		// until publication completes, avoiding a nested connection acquisition.
		if (notification == null) return null;
		return rehydrateActorIdentities(List.of(notification)).getFirst();
	}
	
	
	// kullaniciya ait tum bilgileri getir (yeniden eskiye)
	@Override
	@Transactional
	public Page<NotificationResponseDto> getUserNotifications(UUID userId, int page, int size) {
		Page<NotificationResponseDto> notifications = notificationRepository
				.findByRecipientId(userId, notificationPage(page, size))
				.map(notificationMapper::toDto);
		return rehydrateActorIdentities(notifications);
	}
	
	// kullanicin belirli tipteki tum bildirimlerini filtreleyerek getir
	@Override
	@Transactional
	public Page<NotificationResponseDto> getUserNotificationsByTypes(
			UUID userId,
			Collection<NotificationType> types,
			int page,
			int size
	) {
		Page<NotificationResponseDto> notifications = notificationRepository
				.findByRecipientIdAndTypeIn(
						userId,
						types,
						notificationPage(page, size)
				)
				.map(notificationMapper::toDto);
		return rehydrateActorIdentities(notifications);
	}

	private Pageable notificationPage(int page, int size) {
		if (page < 0 || page > MAX_PAGE || size < 1 || size > MAX_PAGE_SIZE) {
			throw new SoundConnectException(
					ErrorType.VALIDATION_ERROR,
					"page must be between 0 and " + MAX_PAGE
							+ " and size must be between 1 and " + MAX_PAGE_SIZE
			);
		}
		return PageRequest.of(page, size, NOTIFICATION_SORT);
	}
	
	// kullanicinin son 10 bildirimini getir (badge icin hizli erisim)
	@Override
	@Transactional
	public List<NotificationResponseDto> getRecentNotifications(UUID userId) {
		List<Notification> entities = notificationRepository
				.findTop10ByRecipientIdOrderByOccurredAtDescIdDesc(userId);
		return rehydrateActorIdentities(notificationMapper.toDtoList(entities));
	}

	private Page<NotificationResponseDto> rehydrateActorIdentities(Page<NotificationResponseDto> page) {
		List<NotificationResponseDto> hydrated = rehydrateActorIdentities(page.getContent());
		if (hydrated == page.getContent()) return page;
		return new PageImpl<>(hydrated, page.getPageable(), page.getTotalElements());
	}

	private List<NotificationResponseDto> rehydrateActorIdentities(List<NotificationResponseDto> notifications) {
		if (notifications == null || notifications.isEmpty()) return notifications;
		boolean hasIdentitySnapshot = notifications.stream()
				.filter(Objects::nonNull)
				.anyMatch(notification -> hasActorIdentity(notification.type()));
		if (!hasIdentitySnapshot) return notifications;

		LinkedHashSet<UUID> actorIds = new LinkedHashSet<>();
		for (NotificationResponseDto notification : notifications) {
			if (notification != null && hasActorIdentity(notification.type())) {
				parseActorId(notification).ifPresent(actorIds::add);
			}
		}

		Map<UUID, GhostListenerIdentity> ghostIdentities;
		boolean identityResolutionFailed = false;
		try {
			ghostIdentities = actorIds.isEmpty()
					? Map.of()
					: Objects.requireNonNull(
							ghostListenerIdentityBatchResolver.resolve(actorIds),
							"Ghost identity resolver returned null"
					);
		} catch (RuntimeException exception) {
			identityResolutionFailed = true;
			ghostIdentities = Map.of();
			log.warn("Notification actor identity refresh failed; identity snapshots were sanitized. exceptionType={}",
					exception.getClass().getSimpleName());
		}

		final boolean failed = identityResolutionFailed;
		final Map<UUID, GhostListenerIdentity> identities = ghostIdentities;
		return notifications.stream()
				.map(notification -> rehydrateActorIdentity(notification, identities, failed))
				.toList();
	}

	private NotificationResponseDto rehydrateActorIdentity(
			NotificationResponseDto notification,
			Map<UUID, GhostListenerIdentity> ghostIdentities,
			boolean identityResolutionFailed
	) {
		if (notification == null || notification.type() == null) return notification;
		return switch (notification.type()) {
			case DM_NEW_MESSAGE -> rehydrateDmIdentity(
					notification, ghostIdentities, identityResolutionFailed);
			case SOCIAL_NEW_FOLLOWER, SOCIAL_NEW_BAND_FOLLOWER -> rehydrateFollowerIdentity(
					notification, ghostIdentities, identityResolutionFailed);
			default -> notification;
		};
	}

	private NotificationResponseDto rehydrateDmIdentity(
			NotificationResponseDto notification,
			Map<UUID, GhostListenerIdentity> ghostIdentities,
			boolean identityResolutionFailed
	) {
		if (notification == null || notification.type() != NotificationType.DM_NEW_MESSAGE) {
			return notification;
		}
		Optional<UUID> senderId = parseUuid(notification.payload(), "senderId");
		if (identityResolutionFailed || senderId.isEmpty()) {
			return sanitizeUnresolvedDmIdentity(notification);
		}

		GhostListenerIdentity identity = ghostIdentities.get(senderId.get());
		if (identity == null) {
			return removeVisibilityMarker(notification);
		}
		if (identity.visibilityMode() != ListenerVisibilityMode.GHOST) {
			return sanitizeUnresolvedDmIdentity(notification);
		}

		String username = hasText(identity.username()) ? identity.username().trim() : "Kullanici";
		Map<String, Object> payload = mutablePayload(notification.payload());
		payload.put("senderUsername", username);
		if (hasText(identity.profilePictureUrl())) {
			payload.put("senderAvatarUrl", identity.profilePictureUrl().trim());
		} else {
			payload.remove("senderAvatarUrl");
		}
		payload.put("senderVisibilityMode", ListenerVisibilityMode.GHOST.name());
		return copyWithIdentity(
				notification,
				username + " size bir mesaj gönderdi",
				payload
		);
	}

	private NotificationResponseDto rehydrateFollowerIdentity(
			NotificationResponseDto notification,
			Map<UUID, GhostListenerIdentity> ghostIdentities,
			boolean identityResolutionFailed
	) {
		Optional<UUID> followerId = parseUuid(notification.payload(), "followerId");
		if (identityResolutionFailed || followerId.isEmpty()) {
			return sanitizeUnresolvedFollowerIdentity(notification);
		}

		GhostListenerIdentity identity = ghostIdentities.get(followerId.get());
		if (identity == null) {
			return removeFollowerVisibilityMarker(notification);
		}
		if (identity.visibilityMode() != ListenerVisibilityMode.GHOST) {
			return sanitizeUnresolvedFollowerIdentity(notification);
		}

		String username = hasText(identity.username()) ? identity.username().trim() : "Kullanici";
		Map<String, Object> payload = mutablePayload(notification.payload());
		payload.put("followerUsername", username);
		if (hasText(identity.profilePictureUrl())) {
			payload.put("followerAvatarUrl", identity.profilePictureUrl().trim());
		} else {
			payload.remove("followerAvatarUrl");
		}
		payload.put("followerVisibilityMode", ListenerVisibilityMode.GHOST.name());
		return copyWithIdentity(
				notification,
				ghostFollowerTitle(notification.type(), username),
				payload
		);
	}

	private NotificationResponseDto sanitizeUnresolvedDmIdentity(NotificationResponseDto notification) {
		Map<String, Object> payload = mutablePayload(notification.payload());
		payload.remove("senderUsername");
		payload.remove("senderAvatarUrl");
		payload.remove("senderVisibilityMode");
		return copyWithIdentity(notification, SAFE_DM_TITLE, payload);
	}

	private NotificationResponseDto sanitizeUnresolvedFollowerIdentity(NotificationResponseDto notification) {
		Map<String, Object> payload = mutablePayload(notification.payload());
		payload.remove("followerUsername");
		payload.remove("followerAvatarUrl");
		payload.remove("followerVisibilityMode");
		return copyWithIdentity(notification, notification.type().getDefaultTitle(), payload);
	}

	private NotificationResponseDto removeVisibilityMarker(NotificationResponseDto notification) {
		if (notification.payload() == null
				|| !notification.payload().containsKey("senderVisibilityMode")) {
			return notification;
		}
		Map<String, Object> payload = mutablePayload(notification.payload());
		payload.remove("senderVisibilityMode");
		return copyWithIdentity(notification, notification.title(), payload);
	}

	private NotificationResponseDto removeFollowerVisibilityMarker(NotificationResponseDto notification) {
		if (notification.payload() == null
				|| !notification.payload().containsKey("followerVisibilityMode")) {
			return notification;
		}
		Map<String, Object> payload = mutablePayload(notification.payload());
		payload.remove("followerVisibilityMode");
		return copyWithIdentity(notification, notification.title(), payload);
	}

	private NotificationResponseDto copyWithIdentity(
			NotificationResponseDto notification,
			String title,
			Map<String, Object> payload
	) {
		return new NotificationResponseDto(
				notification.id(),
				notification.recipientId(),
				notification.type(),
				title,
				notification.message(),
				notification.read(),
				notification.createdAt(),
				payload.isEmpty()
						? Map.of()
						: Collections.unmodifiableMap(new LinkedHashMap<>(payload))
		);
	}

	private Map<String, Object> mutablePayload(Map<String, Object> payload) {
		return payload == null ? new LinkedHashMap<>() : new LinkedHashMap<>(payload);
	}

	private Optional<UUID> parseActorId(NotificationResponseDto notification) {
		return switch (notification.type()) {
			case DM_NEW_MESSAGE -> parseUuid(notification.payload(), "senderId");
			case SOCIAL_NEW_FOLLOWER, SOCIAL_NEW_BAND_FOLLOWER ->
					parseUuid(notification.payload(), "followerId");
			default -> Optional.empty();
		};
	}

	private Optional<UUID> parseUuid(Map<String, Object> payload, String key) {
		if (payload == null) return Optional.empty();
		Object rawId = payload.get(key);
		if (rawId instanceof UUID id) return Optional.of(id);
		if (!(rawId instanceof String id) || id.isBlank()) return Optional.empty();
		try {
			return Optional.of(UUID.fromString(id.trim()));
		} catch (IllegalArgumentException ignored) {
			return Optional.empty();
		}
	}

	private boolean hasActorIdentity(NotificationType type) {
		return NotificationService.requiresActorIdentityRefresh(type);
	}

	private String ghostFollowerTitle(NotificationType type, String username) {
		return type == NotificationType.SOCIAL_NEW_BAND_FOLLOWER
				? username + " bandını takip etmeye başladı"
				: username + " seni takip etmeye başladı";
	}

	private boolean hasText(String value) {
		return value != null && !value.isBlank();
	}
	
	// Kullaniciya donen unread sayisinda DB source of truth'tur. Projection
	// callback'leri farkli node'larda siradan cikabildigi icin Redis cache hit'i
	// authoritative kabul etmek 15 dakika stale badge uretebilir.
	@Override
	public long getUnreadCount(UUID userId) {
		long count = notificationRepository.countByRecipientIdAndReadIsFalse(userId);
		// Redis only remains a best-effort projection; REST reconciliation always
		// returns the database snapshot calculated above.
		badgeCacheHelper.setUnreadWithTtl(userId, count);
		return count;
	}
	
	// tek bir bildirimi sahiplik kontoruyle birlike okundu olarak isaretle
	@Override
	@Transactional
	public void markAsRead(UUID userId, UUID notificationId) {
		// bildirim kullaniciya mi ait?
		Optional<Notification> opt = notificationRepository.findByIdAndRecipientId(notificationId, userId);
		if (opt.isEmpty()) {
			// bildirim yoksa hata firlat
			throw new SoundConnectException(ErrorType.NOTIFICATION_NOT_FOUND);
		}
		Notification notification = opt.get();
		if (!notification.isRead()) {
			int updated = notificationRepository.markAsRead(notificationId, userId);
			if (updated == 0) {
				log.debug("markAsRead noop: id={}, user={}", notificationId, userId);
			}
		}
		projectUnreadAfterCommit(userId);
	}
	
	
	@Override
	@Transactional
	public int markAllAsRead(UUID userId) {
		// tumunu okundu olarak isaretle -> kac kayit  guncellendigini al
		int updated = notificationRepository.markAllAsRead(userId);
		projectUnreadAfterCommit(userId);
		return updated;
	}

	@Override
	@Transactional
	public int markDmConversationAsRead(UUID userId, UUID conversationId) {
		int updated = notificationRepository.markUnreadDmNotificationsAsReadByConversation(
				userId,
				conversationId.toString()
		);
		projectUnreadAfterCommit(userId);
		return updated;
	}

	private void runAfterCommit(Runnable action) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			action.run();
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				action.run();
			}
		});
	}
	
	
	@Override
	@Transactional
	public boolean deleteById(UUID userId, UUID notificationId) {
		// bildirimin kullaniciya mi ait?
		Optional<Notification> opt = notificationRepository.findByIdAndRecipientId(notificationId, userId);
		if (opt.isEmpty()) {
			throw new SoundConnectException(ErrorType.NOTIFICATION_NOT_FOUND);
		}
		Notification notification = opt.get();
		try {
			// bildirimi sil
			receiptRepository.retainForNotification(notificationId, userId);
			notificationRepository.delete(notification);
		} catch (EmptyResultDataAccessException e) {
			// zaten islinmisse logla veya hata firlat
			log.debug("deleteById already removed: id={}, user={}", notificationId, userId);
			throw new SoundConnectException(ErrorType.NOTIFICATION_NOT_FOUND);
		}
		projectUnreadAfterCommit(userId);
		return true;
	}
	
	@Override
	@Transactional
	public int clearAll(UUID userId) {
		receiptRepository.retainForRecipient(userId);
		int deleted = notificationRepository.deleteByRecipientId(userId);
		projectUnreadAfterCommit(userId);
		return deleted;
	}

	private void projectUnreadAfterCommit(UUID userId) {
		runAfterCommit(() -> {
			long freshUnread;
			try {
				// Read after the mutation transaction commits so every projection is
				// derived from committed database state, not an in-flight snapshot.
				freshUnread = notificationRepository.countByRecipientIdAndReadIsFalse(userId);
			} catch (RuntimeException exception) {
				log.warn("Notification unread recount failed after commit userId={} exceptionType={}",
						userId, exception.getClass().getSimpleName());
				return;
			}
			try {
				badgeCacheHelper.setUnread(userId, freshUnread);
			} catch (RuntimeException exception) {
				log.warn("Notification badge cache projection failed userId={} exceptionType={}",
						userId, exception.getClass().getSimpleName());
			}
			try {
				notificationWebSocketService.sendUnreadBadgeToUser(userId, freshUnread);
			} catch (RuntimeException exception) {
				log.warn("Notification badge WebSocket projection failed userId={} exceptionType={}",
						userId, exception.getClass().getSimpleName());
			}
		});
	}
}
