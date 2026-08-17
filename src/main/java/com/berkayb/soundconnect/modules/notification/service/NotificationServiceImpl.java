package com.berkayb.soundconnect.modules.notification.service;

import com.berkayb.soundconnect.modules.notification.dto.response.NotificationResponseDto;
import com.berkayb.soundconnect.modules.notification.entity.Notification;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.notification.helper.NotificationBadgeCacheHelper;
import com.berkayb.soundconnect.modules.notification.mapper.NotificationMapper;
import com.berkayb.soundconnect.modules.notification.repository.NotificationRepository;
import com.berkayb.soundconnect.modules.notification.websocket.NotificationWebSocketService;
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
			Sort.Order.desc("occurredAt"),
			Sort.Order.desc("id")
	);
	
	private final NotificationRepository notificationRepository;
	private final NotificationMapper notificationMapper;
	private final NotificationBadgeCacheHelper badgeCacheHelper;
	private final NotificationWebSocketService notificationWebSocketService;
	
	
	// kullaniciya ait tum bilgileri getir (yeniden eskiye)
	@Override
	public Page<NotificationResponseDto> getUserNotifications(UUID userId, int page, int size) {
		return notificationRepository
				.findByRecipientId(userId, notificationPage(page, size))
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
				.findByRecipientIdAndTypeIn(
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
		List<Notification> entities = notificationRepository
				.findTop10ByRecipientIdOrderByOccurredAtDescIdDesc(userId);
		return notificationMapper.toDtoList(entities);
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
