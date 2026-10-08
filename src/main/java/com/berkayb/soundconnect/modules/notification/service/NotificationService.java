package com.berkayb.soundconnect.modules.notification.service;


import com.berkayb.soundconnect.modules.notification.dto.response.NotificationResponseDto;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import org.springframework.data.domain.Page;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface NotificationService {
	static boolean requiresActorIdentityRefresh(NotificationType type) {
		return com.berkayb.soundconnect.modules.notification.support.BandNotificationIdentity.applies(type)
				|| type == NotificationType.DM_NEW_MESSAGE
				|| type == NotificationType.SOCIAL_NEW_FOLLOWER
				|| type == NotificationType.SOCIAL_NEW_BAND_FOLLOWER
				|| type == NotificationType.SOCIAL_LIKE
				|| type == NotificationType.SOCIAL_COMMENT;
	}

	static boolean requiresActorIdentityRefresh(NotificationType type, java.util.Map<String,Object> payload) {
		return requiresActorIdentityRefresh(type)
				&& ((type != NotificationType.SOCIAL_LIKE && type != NotificationType.SOCIAL_COMMENT)
				|| com.berkayb.soundconnect.modules.notification.support.MediaNotificationIdentity.applies(type, payload));
	}

	// Refresh privacy-sensitive actor snapshots immediately before realtime delivery.
	NotificationResponseDto refreshActorIdentityForDelivery(NotificationResponseDto notification);

	// Read one current recipient-visible notification without acknowledging it.
	NotificationResponseDto getUserNotification(UUID userId, UUID notificationId);
	
	// kullanicinin bildirimlerini yeniden eskiye sayfali getir
	Page<NotificationResponseDto> getUserNotifications(UUID userId, int page, int size);
	
	// bildirim tipine gore filtreleyerek listeleme
	Page<NotificationResponseDto> getUserNotificationsByTypes(
			UUID userId,
			Collection<NotificationType> types,
			int page,
			int size
	);
	
	// hizli UI icin son 10 bildirim (badge/preview listesi)
	List<NotificationResponseDto> getRecentNotifications(UUID userId);
	
	// okunmamis bildirim sayisi (badge)
	long getUnreadCount(UUID userId);

	// Read-only OS reconciliation; only returns IDs from the bounded request.
	List<UUID> getDismissedDeliveryIds(UUID userId, List<UUID> notificationIds);
	
	// tum okunmamislari okunduya cek
	int markAllAsRead(UUID userId);
	
	// tek bir bildirimi sahiplik kontrolu ile okundu olarak isaretler
	void markAsRead(UUID userId, UUID notificationId);

	// DM konusmasi acildiginda o konusmaya ait okunmamis DM bildirimlerini okundu yapar
	int markDmConversationAsRead(UUID userId, UUID conversationId);

	int markDmMessageAsRead(UUID userId, UUID messageId);
	
	// sahiplik kontroluyle tek bir bildirimi siler
	boolean deleteById(UUID userId, UUID notificationId);
	
	// kullanicinin tum bildirimlerini siler
	int clearAll(UUID userId);
	
}
