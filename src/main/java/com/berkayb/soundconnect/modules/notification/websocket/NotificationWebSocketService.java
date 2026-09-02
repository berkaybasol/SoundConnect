package com.berkayb.soundconnect.modules.notification.websocket;


import com.berkayb.soundconnect.modules.notification.dto.response.NotificationResponseDto;

import java.util.UUID;

/**
 * STOMP uzerinden kullanicilara real-time bildirim yayini.
 * Canonical destination: {@code /topic/notifications.<userId>}
 */
public interface NotificationWebSocketService {
	
	// tek bir kullaniciya bildirim push eder
	void sendNotificationToUser(UUID userID, NotificationResponseDto payload);
	
	/**
	 * İsteğe bağlı: badge (unread count) güncellemesi push etmek istersek.
	 * UI tarafı ayrı bir kanala abone olabilir:
	 * {@code /topic/notifications.<userId>.badge}
	 */
	default void sendUnreadBadgeToUser(UUID userId, long unreadCount) {
		// opsiyonel; implementasyon tarafında override edilebilir
	}
}
