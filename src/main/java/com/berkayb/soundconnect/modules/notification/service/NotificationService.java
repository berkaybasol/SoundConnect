package com.berkayb.soundconnect.modules.notification.service;


import com.berkayb.soundconnect.modules.notification.dto.response.NotificationResponseDto;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import org.springframework.data.domain.Page;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface NotificationService {
	
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
	
	// tum okunmamislari okunduya cek
	int markAllAsRead(UUID userId);
	
	// tek bir bildirimi sahiplik kontrolu ile okundu olarak isaretler
	void markAsRead(UUID userId, UUID notificationId);

	// DM konusmasi acildiginda o konusmaya ait okunmamis DM bildirimlerini okundu yapar
	int markDmConversationAsRead(UUID userId, UUID conversationId);
	
	// sahiplik kontroluyle tek bir bildirimi siler
	boolean deleteById(UUID userId, UUID notificationId);
	
	// kullanicinin tum bildirimlerini siler
	int clearAll(UUID userId);
	
}
