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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationServiceImpl implements NotificationService {
	private static final int MAX_PAGE = 1000;
	private static final int MAX_PAGE_SIZE = 100;
	private static final Sort NOTIFICATION_SORT = Sort.by(
			Sort.Order.desc("createdAt"),
			Sort.Order.desc("id")
	);
	
	private final NotificationRepository notificationRepository;
	private final NotificationMapper notificationMapper;
	private final NotificationBadgeCacheHelper badgeCacheHelper;
	
	
	// kullaniciya ait tum bilgileri getir (yeniden eskiye)
	@Override
	public Page<NotificationResponseDto> getUserNotifications(UUID userId, int page, int size) {
		return notificationRepository
				.findByRecipientIdOrderByCreatedAtDesc(userId, notificationPage(page, size))
				.map(notificationMapper::toDto);
	}
	
	// kullanicin belirli tipteki tum bildirimlerini filtreleyerek getir
	@Override
	public Page<NotificationResponseDto> getUserNotificationsByTypes(
			UUID userId,
			Collection<NotificationType> types,
			int page,
			int size
	) {
		return notificationRepository
				.findByRecipientIdAndTypeInOrderByCreatedAtDesc(
						userId,
						types,
						notificationPage(page, size)
				)
				.map(notificationMapper::toDto);
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
			// DB guncel sayiyi verdigi icin cache'i dogrudan senkronla.
			badgeCacheHelper.setUnread(userId, freshUnread);
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
	public int markDmConversationAsRead(UUID userId, UUID conversationId) {
		int updated = notificationRepository.markUnreadDmNotificationsAsReadByConversation(
				userId,
				conversationId.toString()
		);
		if (updated > 0) {
			long freshUnread = notificationRepository.countByRecipientIdAndReadIsFalse(userId);
			runAfterCommit(() -> {
				try {
					badgeCacheHelper.setUnread(userId, freshUnread);
				} catch (RuntimeException exception) {
					log.warn("DM notification badge projection failed userId={} exceptionType={}",
					         userId, exception.getClass().getSimpleName());
				}
			});
		}
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
			// eger silinen bildirim unread ise -> cache'i DB'deki guncel sayiyla senkronla
			long freshUnread = notificationRepository.countByRecipientIdAndReadIsFalse(userId);
			badgeCacheHelper.setUnread(userId, freshUnread);
		}
		return true;
	}
	
	@Override
	@Transactional
	public int clearAll(UUID userId) {
		int deleted = notificationRepository.deleteByRecipientId(userId);
		badgeCacheHelper.setUnread(userId, 0);
		return deleted;
	}
}
