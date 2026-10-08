package com.berkayb.soundconnect.modules.message.dm.service;

import com.berkayb.soundconnect.modules.message.dm.dto.request.DMMessageRequestDto;
import com.berkayb.soundconnect.modules.message.dm.entity.DMConversation;
import com.berkayb.soundconnect.modules.message.dm.event.DmMessageEventPublisher;
import com.berkayb.soundconnect.modules.message.dm.mapper.DMMessageMapper;
import com.berkayb.soundconnect.modules.message.dm.repository.DMConversationRepository;
import com.berkayb.soundconnect.modules.message.dm.repository.DMMessageRepository;
import com.berkayb.soundconnect.modules.notification.entity.Notification;
import com.berkayb.soundconnect.modules.notification.helper.NotificationBadgeCacheHelper;
import com.berkayb.soundconnect.modules.notification.mapper.NotificationMapper;
import com.berkayb.soundconnect.modules.notification.repository.NotificationRepository;
import com.berkayb.soundconnect.modules.notification.service.NotificationService;
import com.berkayb.soundconnect.modules.notification.service.TransactionalNotificationService;
import com.berkayb.soundconnect.modules.notification.websocket.NotificationWebSocketService;
import com.berkayb.soundconnect.modules.profile.shared.resolver.dto.UserProfileTargetDto;
import com.berkayb.soundconnect.modules.profile.shared.resolver.dto.UserProfilesResolveResponseDto;
import com.berkayb.soundconnect.modules.profile.shared.resolver.service.PublicProfileResolverService;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.modules.user.support.AccountDeliveryFence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DataJpaTest(properties = {
        "spring.config.import=",
        "spring.datasource.url=jdbc:h2:mem:dm-notification-${random.uuid};MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.datasource.hikari.connection-init-sql=",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ContextConfiguration(classes = DmNotificationTransactionIT.ConfigurationForTest.class)
class DmNotificationTransactionIT {
    @Autowired DMMessageServiceImpl messages;
    @Autowired DMMessageRepository messageRepository;
    @Autowired DMConversationRepository conversations;
    @Autowired NotificationRepository inbox;
    @Autowired PlatformTransactionManager manager;
    @MockitoSpyBean DmNotificationService dmNotifications;
    @MockitoBean DMMessageMapper messageMapper;
    @MockitoBean DmMessageEventPublisher events;
    @MockitoBean NotificationService notificationService;
    @MockitoBean AccountDeliveryFence accounts;
    @MockitoBean DmSendReceiptStore sendReceipts;
    @MockitoBean com.berkayb.soundconnect.modules.message.dm.abuse.DmRateLimitGuard rateLimit;
    @MockitoBean UserRepository users;
    @MockitoBean PublicProfileResolverService profiles;
    @MockitoBean NotificationMapper notificationMapper;
    @MockitoBean NotificationBadgeCacheHelper badges;
    @MockitoBean NotificationWebSocketService websocket;
    UUID sender, recipient, conversation;

    @BeforeEach
    void setup() {
        messageRepository.deleteAll();
        conversations.deleteAll();
        inbox.deleteAll();
        sender = UUID.randomUUID();
        recipient = UUID.randomUUID();
        conversation = conversations.saveAndFlush(DMConversation.builder().userAId(sender).userBId(recipient).build()).getId();
        when(profiles.resolveByUserId(sender)).thenReturn(new UserProfilesResolveResponseDto(sender,
                List.of(new UserProfileTargetDto("MUSICIAN", UUID.randomUUID(), "Musician", "https://example.test/a"))));
    }

    @Test
    void sendCommitsMessageAndInboxInOneTransactionBeforeRealtimeProjection() {
        messages.sendMessage(request(), sender);
        var storedMessage = messageRepository.findAll().getFirst();
        var storedNotification = inbox.findAll().getFirst();
        assertThat(storedNotification.getSourceEventId())
                .isEqualTo(DmNotificationService.eventId(storedMessage.getId(), recipient));
        assertThat(storedNotification.getPayload()).containsEntry("messageId", storedMessage.getId().toString());
        assertThat(storedNotification.getRecipientId()).isEqualTo(recipient);
        assertThat(storedNotification.isRead()).isFalse();
        assertThat(conversations.findById(conversation).orElseThrow().getLastMessageAt()).isNotNull();
        verify(events).publishMessageSentEvent(any());
    }

    @Test
    void outerRollbackRemovesActualMessageAndInboxAndDoesNotDeliver() {
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            messages.sendMessage(request(), sender);
            assertThat(messageRepository.count()).isOne();
            assertThat(inbox.count()).isOne();
            verifyNoInteractions(websocket);
            status.setRollbackOnly();
        });
        assertThat(messageRepository.count()).isZero();
        assertThat(inbox.count()).isZero();
        assertThat(conversations.findById(conversation).orElseThrow().getLastMessageAt()).isNull();
        verifyNoInteractions(websocket);
    }

    @Test
    void notificationFailureRollsBackAlreadyFlushedMessageAndDoesNotPublishSentEvent() {
        DmNotificationService target = AopTestUtils.getUltimateTargetObject(dmNotifications);
        doThrow(new IllegalStateException("Inbox unavailable")).when(target).persist(any());
        assertThatThrownBy(() -> messages.sendMessage(request(), sender))
                .isInstanceOf(IllegalStateException.class).hasMessage("Inbox unavailable");
        assertThat(messageRepository.count()).isZero();
        assertThat(inbox.count()).isZero();
        assertThat(conversations.findById(conversation).orElseThrow().getLastMessageAt()).isNull();
        verifyNoInteractions(events, websocket);
    }

    private DMMessageRequestDto request() {
        return new DMMessageRequestDto(conversation, recipient, "hello", "text");
    }

    @Configuration(proxyBeanMethods = false)
    @EntityScan(basePackageClasses = {DMConversation.class, Notification.class})
    @EnableJpaRepositories(basePackageClasses = {DMConversationRepository.class, NotificationRepository.class})
    @EnableJpaAuditing
    @Import({DMMessageServiceImpl.class, DmNotificationService.class, TransactionalNotificationService.class,
            com.berkayb.soundconnect.support.DeliveryPolicyTestSupport.Config.class,
            com.berkayb.soundconnect.modules.notification.support.NotificationAudienceTestSchema.class})
    static class ConfigurationForTest { }
}
