package com.berkayb.soundconnect.modules.notification.service;

import com.berkayb.soundconnect.modules.notification.helper.NotificationBadgeCacheHelper;
import com.berkayb.soundconnect.modules.notification.repository.NotificationRepository;
import com.berkayb.soundconnect.modules.notification.repository.NotificationReceiptRepository;
import com.berkayb.soundconnect.modules.notification.websocket.NotificationWebSocketService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationCleanupService {
	
	private final NotificationRepository notificationRepository;
	private final NotificationBadgeCacheHelper badgeCacheHelper;
	private final NotificationWebSocketService notificationWebSocketService;
	private final NotificationReceiptRepository receiptRepository;
	
	@Value("${app.notification.retention-days:30}")
	private long retentionDays;
	
	@Scheduled(cron = "${app.notification.cleanup-cron:0 20 4 * * *}")
	@Transactional
	public void cleanupExpiredNotifications() {
		if (retentionDays <= 0) {
			log.debug("Notification cleanup skipped. retentionDays={}", retentionDays);
			return;
		}
		
		LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
		List<UUID> affectedUsers = notificationRepository.findDistinctRecipientIdsByCreatedAtBefore(cutoff);
		if (affectedUsers.isEmpty()) {
			log.debug("Notification cleanup found no expired records. cutoff={}", cutoff);
			return;
		}
		
		receiptRepository.retainBeforeCutoff(cutoff);
		int deleted = notificationRepository.deleteByCreatedAtBefore(cutoff);
		List<UUID> committedUsers = List.copyOf(affectedUsers);
		runAfterCommit(() -> {
			committedUsers.forEach(this::projectCommittedUnread);
			log.info(
					"Notification cleanup deleted {} records older than {} days for {} users",
					deleted,
					retentionDays,
					committedUsers.size()
			);
		});
	}

	private void projectCommittedUnread(UUID userId) {
		long freshUnread;
		try {
			freshUnread = notificationRepository.countByRecipientIdAndReadIsFalse(userId);
		} catch (RuntimeException exception) {
			log.warn("Notification cleanup unread recount failed userId={} exceptionType={}",
					userId, exception.getClass().getSimpleName());
			return;
		}
		try {
			badgeCacheHelper.setUnread(userId, freshUnread);
		} catch (RuntimeException exception) {
			log.warn("Notification cleanup badge cache projection failed userId={} exceptionType={}",
					userId, exception.getClass().getSimpleName());
		}
		try {
			notificationWebSocketService.sendUnreadBadgeToUser(userId, freshUnread);
		} catch (RuntimeException exception) {
			log.warn("Notification cleanup badge WebSocket projection failed userId={} exceptionType={}",
					userId, exception.getClass().getSimpleName());
		}
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
}
