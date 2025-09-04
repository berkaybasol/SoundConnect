package com.berkayb.soundconnect.modules.notification.service;


import com.berkayb.soundconnect.modules.notification.dto.response.NotificationResponseDto;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface NotificationService {
	
	// kullanicinin bildirimlerini yeniden eskiye sayfali getir
	Page<NotificationResponseDto> getUserNotifications(UUID userId, Pageable pageable);
	
	// bildirim tipine gore filtreleyerek listeleme
	Page<NotificationResponseDto> getUserNotificationsByTypes(UUID userId, Collection<NotificationType> types, Pageable pageable);
	
	// hizli UI icin son 10 bildirim (badge/preview listesi)
	List<NotificationResponseDto> getRecentNotifications(UUID userId);
	
	// okunmamis bildirim sayisi (badge)
	long getUnreadCount(UUID userId);
	
	// tum okunmamislari okunduya cek
	int markAllAsRead(UUID userId);
	
	// tek bir bildirimi sahiplik kontrolu ile okundu olarak isaretler
	void markAsRead(UUID userId, UUID notificationId);
	
	// sahiplik kontroluyle tek bir bildirimi siler
	boolean deleteById(UUID userId, UUID notificationId);
	
	
}