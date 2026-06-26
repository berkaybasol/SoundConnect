package com.berkayb.soundconnect.modules.notification.service;

import com.berkayb.soundconnect.modules.notification.helper.NotificationBadgeCacheHelper;
import com.berkayb.soundconnect.modules.notification.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationCleanupService {
	
	private final NotificationRepository notificationRepository;
	private final NotificationBadgeCacheHelper badgeCacheHelper;
	
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
		
		int deleted = notificationRepository.deleteByCreatedAtBefore(cutoff);
		for (UUID userId : affectedUsers) {
			long freshUnread = notificationRepository.countByRecipientIdAndReadIsFalse(userId);
			badgeCacheHelper.setUnread(userId, freshUnread);
		}
		
		log.info(
				"Notification cleanup deleted {} records older than {} days for {} users",
				deleted,
				retentionDays,
				affectedUsers.size()
		);
	}
}
