package com.berkayb.soundconnect.modules.notification.service;

import com.berkayb.soundconnect.modules.notification.helper.NotificationBadgeCacheHelper;
import com.berkayb.soundconnect.modules.notification.repository.NotificationRepository;
import com.berkayb.soundconnect.modules.notification.repository.NotificationReceiptRepository;
import com.berkayb.soundconnect.modules.notification.websocket.NotificationWebSocketService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationCleanupServiceTest {

    @Mock NotificationRepository notificationRepository;
    @Mock NotificationBadgeCacheHelper badgeCacheHelper;
    @Mock NotificationWebSocketService notificationWebSocketService;
    @Mock NotificationReceiptRepository receiptRepository;

    @InjectMocks NotificationCleanupService service;

    @Test
    void cleanupProjectsDatabaseUnreadOnlyAfterCommit() {
        UUID userId = UUID.randomUUID();
        ReflectionTestUtils.setField(service, "retentionDays", 30L);
        when(notificationRepository.findDistinctRecipientIdsByCreatedAtBefore(any(LocalDateTime.class)))
                .thenReturn(List.of(userId));
        when(notificationRepository.deleteByCreatedAtBefore(any(LocalDateTime.class))).thenReturn(3);
        when(notificationRepository.countByRecipientIdAndReadIsFalse(userId)).thenReturn(2L);
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.cleanupExpiredNotifications();

            var ordered = org.mockito.Mockito.inOrder(receiptRepository, notificationRepository);
            ordered.verify(receiptRepository).retainBeforeCutoff(any(LocalDateTime.class));
            ordered.verify(notificationRepository).deleteByCreatedAtBefore(any(LocalDateTime.class));

            verify(notificationRepository, never()).countByRecipientIdAndReadIsFalse(any());
            verifyNoInteractions(badgeCacheHelper, notificationWebSocketService);
            List<TransactionSynchronization> synchronizations =
                    TransactionSynchronizationManager.getSynchronizations();
            assertThat(synchronizations).hasSize(1);

            synchronizations.getFirst().afterCommit();

            verify(notificationRepository).countByRecipientIdAndReadIsFalse(userId);
            verify(badgeCacheHelper).setUnread(userId, 2L);
            verify(notificationWebSocketService).sendUnreadBadgeToUser(userId, 2L);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void cleanupRollbackDoesNotTouchCacheOrWebSocket() {
        UUID userId = UUID.randomUUID();
        ReflectionTestUtils.setField(service, "retentionDays", 30L);
        when(notificationRepository.findDistinctRecipientIdsByCreatedAtBefore(any(LocalDateTime.class)))
                .thenReturn(List.of(userId));
        when(notificationRepository.deleteByCreatedAtBefore(any(LocalDateTime.class))).thenReturn(1);
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.cleanupExpiredNotifications();

            List<TransactionSynchronization> synchronizations =
                    TransactionSynchronizationManager.getSynchronizations();
            assertThat(synchronizations).hasSize(1);
            synchronizations.getFirst().afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

            verify(notificationRepository, never()).countByRecipientIdAndReadIsFalse(any());
            verifyNoInteractions(badgeCacheHelper, notificationWebSocketService);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
