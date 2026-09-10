package com.berkayb.soundconnect.modules.notification.service;

import com.berkayb.soundconnect.modules.notification.entity.Notification;
import com.berkayb.soundconnect.modules.notification.helper.NotificationBadgeCacheHelper;
import com.berkayb.soundconnect.modules.notification.mapper.NotificationMapper;
import com.berkayb.soundconnect.modules.notification.repository.NotificationRepository;
import com.berkayb.soundconnect.modules.notification.repository.NotificationReceiptRepository;
import com.berkayb.soundconnect.modules.notification.websocket.NotificationWebSocketService;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationInboundEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.LinkedHashMap;
import java.util.Objects;

/**
 * Persists an in-app notification in the caller's domain transaction. A failed
 * write must abort that transaction; broker availability cannot lose the inbox
 * record. Realtime delivery is a best-effort projection of the committed inbox.
 * Mail-producing workflows continue to use their durable delivery pipelines.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionalNotificationService {
    private final NotificationRepository notificationRepository;
    private final NotificationMapper notificationMapper;
    private final NotificationBadgeCacheHelper badgeCacheHelper;
    private final NotificationWebSocketService notificationWebSocketService;
    private final NotificationService notificationService;
    private final NotificationReceiptRepository receiptRepository;
    private final NotificationDeliveryPolicy deliveryPolicy;

    @Transactional(propagation = Propagation.MANDATORY)
    public void persistInCurrentTransaction(NotificationInboundEvent event) {
        Objects.requireNonNull(event, "event is required");
        Objects.requireNonNull(event.eventId(), "event.eventId is required");
        Objects.requireNonNull(event.recipientId(), "event.recipientId is required");
        Objects.requireNonNull(event.type(), "event.type is required");
        Objects.requireNonNull(event.occurredAt(), "event.occurredAt is required");
        if (!Boolean.FALSE.equals(event.emailForce())) {
            throw new IllegalArgumentException("Transactional in-app notifications require emailForce=false");
        }
        String title = event.title() == null || event.title().isBlank()
                ? event.type().getDefaultTitle() : event.title();
        String message = event.message() == null ? "" : event.message();
        if (title.length() > 160 || message.length() > 1000) {
            throw new IllegalArgumentException("Notification title/message exceeds storage limits");
        }
        boolean eligible = deliveryPolicy.eligible(event);
        if (receiptRepository.claim(event.eventId(), event.recipientId()) == 0) return;
        if (notificationRepository.existsBySourceEventId(event.eventId())) return;
        if (!eligible) return;

        Notification saved = notificationRepository.saveAndFlush(Notification.builder()
                .sourceEventId(event.eventId())
                .recipientId(event.recipientId())
                .type(event.type())
                .title(title)
                .message(message)
                .payload(event.payload() == null ? null : new LinkedHashMap<>(event.payload()))
                .occurredAt(event.occurredAt())
                .read(false)
                .build());
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                deliveryPolicy.schedule(event, saved.getId(), () -> dispatchCommitted(saved));
            }
        });
    }

    private void dispatchCommitted(Notification notification) {
        Long unread = null;
        try {
            unread = notificationRepository.countByRecipientIdAndReadIsFalse(notification.getRecipientId());
            badgeCacheHelper.setUnreadWithTtl(notification.getRecipientId(), unread);
        } catch (RuntimeException exception) {
            log.warn("Committed inbox badge projection failed. notificationId={}, exceptionType={}",
                    notification.getId(), exception.getClass().getSimpleName());
        }
        try {
            var dto = notificationMapper.toDto(notification);
            if (dto != null && NotificationService.requiresActorIdentityRefresh(dto.type())) {
                dto = notificationService.refreshActorIdentityForDelivery(dto);
            }
            notificationWebSocketService.sendNotificationToUser(
                    notification.getRecipientId(), dto);
        } catch (RuntimeException exception) {
            log.warn("Committed inbox realtime delivery failed. notificationId={}, exceptionType={}",
                    notification.getId(), exception.getClass().getSimpleName());
        }
        if (unread != null) {
            try {
                notificationWebSocketService.sendUnreadBadgeToUser(notification.getRecipientId(), unread);
            } catch (RuntimeException exception) {
                log.warn("Committed inbox realtime badge failed. notificationId={}, exceptionType={}",
                        notification.getId(), exception.getClass().getSimpleName());
            }
        }
    }
}
