package com.berkayb.soundconnect.modules.notification.listener;

import com.berkayb.soundconnect.modules.notification.dto.response.NotificationResponseDto;
import com.berkayb.soundconnect.modules.notification.entity.Notification;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.notification.helper.NotificationBadgeCacheHelper;
import com.berkayb.soundconnect.modules.notification.mapper.NotificationMapper;
import com.berkayb.soundconnect.modules.notification.repository.NotificationRepository;
import com.berkayb.soundconnect.modules.notification.websocket.NotificationWebSocketService;
import com.berkayb.soundconnect.shared.mail.producer.MailProducer;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationInboundEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ContextConfiguration(classes = NotificationEventDeduplicationIT.JpaTestConfiguration.class)
class NotificationEventDeduplicationIT {

    @Autowired
    private NotificationRepository notificationRepository;

    private NotificationBadgeCacheHelper badgeCacheHelper;
    private NotificationWebSocketService webSocketService;
    private MailProducer mailProducer;
    private NotificationEventListener listener;

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll();
        badgeCacheHelper = mock(NotificationBadgeCacheHelper.class);
        NotificationMapper mapper = mock(NotificationMapper.class);
        webSocketService = mock(NotificationWebSocketService.class);
        mailProducer = mock(MailProducer.class);
        when(mapper.toDto(any(Notification.class))).thenAnswer(invocation -> {
            Notification notification = invocation.getArgument(0);
            return new NotificationResponseDto(
                    notification.getId(),
                    notification.getRecipientId(),
                    notification.getType(),
                    notification.getTitle(),
                    notification.getMessage(),
                    notification.isRead(),
                    notification.getOccurredAt(),
                    notification.getPayload()
            );
        });
        listener = new NotificationEventListener(
                notificationRepository,
                badgeCacheHelper,
                mapper,
                webSocketService,
                mailProducer
        );
    }

    @Test
    void duplicateStableEventIdCreatesAndDispatchesExactlyOneNotification() {
        UUID eventId = UUID.randomUUID();
        UUID recipientId = UUID.randomUUID();
        NotificationInboundEvent event = NotificationInboundEvent.builder()
                .eventId(eventId)
                .recipientId(recipientId)
                .type(NotificationType.COLLAB_APPLICATION_RECEIVED)
                .title("Yeni basvuru")
                .message("Ilanina yeni bir basvuru geldi.")
                .payload(Map.of(
                        "module", "COLLAB",
                        "action", "APPLICATION_RECEIVED",
                        "listingId", UUID.randomUUID()
                ))
                .emailForce(false)
                .occurredAt(Instant.parse("2026-08-11T00:00:00Z"))
                .build();

        listener.handle(event);
        listener.handle(event);

        assertThat(notificationRepository.count()).isEqualTo(1);
        Notification stored = notificationRepository.findBySourceEventId(eventId).orElseThrow();
        assertThat(stored.getRecipientId()).isEqualTo(recipientId);
        assertThat(stored.getSourceEventId()).isEqualTo(eventId);
        assertThat(stored.getOccurredAt()).isEqualTo(event.occurredAt());
        verify(webSocketService, times(1)).sendNotificationToUser(any(UUID.class), any());
        verify(webSocketService, times(1)).sendUnreadBadgeToUser(recipientId, 1L);
        verify(badgeCacheHelper, times(1)).setUnreadWithTtl(recipientId, 1L);
        verify(mailProducer, never()).send(any());
    }

    @Configuration(proxyBeanMethods = false)
    @EntityScan(basePackageClasses = Notification.class)
    @EnableJpaRepositories(basePackageClasses = NotificationRepository.class)
    static class JpaTestConfiguration {
    }
}
