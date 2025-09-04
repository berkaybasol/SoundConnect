package com.berkayb.soundconnect.modules.notification.service;

import com.berkayb.soundconnect.modules.notification.dto.response.NotificationResponseDto;
import com.berkayb.soundconnect.modules.notification.entity.Notification;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.notification.helper.NotificationBadgeCacheHelper;
import com.berkayb.soundconnect.modules.notification.mapper.NotificationMapper;
import com.berkayb.soundconnect.modules.notification.repository.NotificationRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationServiceImpl implements NotificationService {
	
	private final NotificationRepository notificationRepository;
	private final NotificationMapper notificationMapper;
	private final NotificationBadgeCacheHelper badgeCacheHelper;
	
	
	// kullaniciya ait tum bilgileri getir (yeniden eskiye)
	@Override
	public Page<NotificationResponseDto> getUserNotifications(UUID userId, Pageable pageable) {
		return notificationRepository
				.findByRecipientIdOrderByCreatedAtDesc(userId, pageable)
				.map(notificationMapper::toDto);
	}
	
	// kullanicin belirli tipteki tum bildirimlerini filtreleyerek getir
	@Override
	public Page<NotificationResponseDto> getUserNotificationsByTypes(UUID userId, Collection<NotificationType> types, Pageable pageable) {
		return notificationRepository
				.findByRecipientIdAndTypeInOrderByCreatedAtDesc(userId, types, pageable)
				.map(notificationMapper::toDto);
	}
	
	// kullanicinin son 10 bildirimini getir (badge icin hizli erisim)
	@Override
	public List<NotificationResponseDto> getRecentNotifications(UUID userId) {
		List<Notification> entities = notificationRepository.findTop10ByRecipientIdOrderByCreatedAtDesc(userId);
		return notificationMapper.toDtoList(entities);
	}
	
	// kullanicinin okunmamis bildirim sayisini getir (once cach'e bakilir)
	@Override
	public long getUnreadCount(UUID userId) {
		// Redis cache'ten unread sayisini al
		Long cached = badgeCacheHelper.getCacheUnread(userId);
		if (cached != null) {
			return cached;
		}
		// Cache yoksa veritabanindan say -> cache'e yaz -> sonucu dondur
		long count = notificationRepository.countByRecipientIdAndReadIsFalse(userId);
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
		if (notification.isRead()) {
			// zaten okunmussa hata firlat
			throw new SoundConnectException(ErrorType.NOTIFICATION_ALREADY_READ);
		}
		// bildirimi okundu olarak isaretle
		int updated =  notificationRepository.markAsRead(notificationId, userId);
		if (updated == 1) {
			long freshUnread = notificationRepository.countByRecipientIdAndReadIsFalse(userId);
			// badge sayacini guvenli azalt
			badgeCacheHelper.decrementUnreadSafely(userId,1,freshUnread);
		} else {
			log.debug("markAsRead noop: id={}, user={}", notificationId, userId);
		}
	}
	
	
	@Override
	@Transactional
	public int markAllAsRead(UUID userId) {
		// tumunu okundu olarak isaretle -> kac kayit  guncellendigini al
		int updated = notificationRepository.markAllAsRead(userId);
		if (updated > 0) {
			// cache'deki unread sayacini sifirla
			badgeCacheHelper.setUnread(userId, 0);
		}
		return updated;
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
		boolean wasUnread = !notification.isRead(); // silinnen bildirim okunmamis mi?
		
		try {
			// bildirimi sil
			notificationRepository.delete(notification);
		} catch (EmptyResultDataAccessException e) {
			// zaten islinmisse logla veya hata firlat
			log.debug("deleteById already removed: id={}, user={}", notificationId, userId);
			throw new SoundConnectException(ErrorType.NOTIFICATION_NOT_FOUND);
		}
		if (wasUnread) {
			// eger silinen bildirim unread ise -> cache'deki sayaci azalt
			long freshUnread = notificationRepository.countByRecipientIdAndReadIsFalse(userId);
			badgeCacheHelper.decrementUnreadSafely(userId, 1, freshUnread);
		}
		return true;
	}
}