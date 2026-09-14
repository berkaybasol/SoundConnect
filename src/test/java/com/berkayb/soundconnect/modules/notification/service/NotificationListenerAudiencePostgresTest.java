package com.berkayb.soundconnect.modules.notification.service;

import com.berkayb.soundconnect.modules.comment.support.CommentTargetAccessGuard;
import com.berkayb.soundconnect.modules.media.mapper.MediaAssetMapper;
import com.berkayb.soundconnect.modules.media.repository.MediaAssetRepository;
import com.berkayb.soundconnect.modules.notification.dto.response.NotificationResponseDto;
import com.berkayb.soundconnect.modules.notification.entity.Notification;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.notification.helper.NotificationBadgeCacheHelper;
import com.berkayb.soundconnect.modules.notification.listener.NotificationEventListener;
import com.berkayb.soundconnect.modules.notification.mapper.NotificationMapperImpl;
import com.berkayb.soundconnect.modules.notification.repository.NotificationReceiptRepository;
import com.berkayb.soundconnect.modules.notification.repository.NotificationRepository;
import com.berkayb.soundconnect.modules.notification.support.NotificationAudienceTestSchema;
import com.berkayb.soundconnect.modules.notification.websocket.NotificationWebSocketService;
import com.berkayb.soundconnect.modules.profile.shared.identity.GhostListenerIdentityBatchResolver;
import com.berkayb.soundconnect.modules.user.support.AccountDeliveryFence;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.mail.adapter.MailSenderClient;
import com.berkayb.soundconnect.shared.mail.dto.MailSendRequest;
import com.berkayb.soundconnect.shared.mail.enums.MailKind;
import com.berkayb.soundconnect.shared.mail.producer.MailProducer;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationInboundEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real recipient roles, SQL pagination, receipt and final-delivery boundaries in a disposable database. */
@Testcontainers
@DataJpaTest(properties = {
        "spring.config.import=", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.hikari.connection-init-sql=",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ContextConfiguration(classes = NotificationListenerAudiencePostgresTest.Config.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class NotificationListenerAudiencePostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4")
            .withDatabaseName("notification_listener_audience_test").withUsername("audience_test").withPassword("audience_test");
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }
    private static final Instant NOW = Instant.parse("2026-09-14T10:00:00Z");
    @Autowired NotificationRepository notifications;
    @Autowired NotificationReceiptRepository receipts;
    @Autowired NotificationService service;
    @Autowired NotificationEventListener broker;
    @Autowired TransactionalNotificationService transactional;
    @Autowired NotificationDeliveryPolicy policy;
    @Autowired AfterCommitDeliveryExecutor executor;
    @Autowired NotificationMailDelivery mailDelivery;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean NotificationBadgeCacheHelper badges;
    @MockitoBean NotificationWebSocketService websocket;
    @MockitoBean GhostListenerIdentityBatchResolver identities;
    @MockitoBean MailProducer mail;
    @MockitoBean MailSenderClient mailSender;
    TransactionTemplate transaction;
    UUID recipient;

    @BeforeEach void setup() throws Exception {
        drain();
        transaction = new TransactionTemplate(transactionManager);
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertThat(connection.getCatalog()).isEqualTo("notification_listener_audience_test");
        }
        jdbc.execute("create table if not exists tbl_user(id uuid primary key, status text, email_verified boolean, erased_at timestamp)");
        jdbc.execute("truncate tbl_notification,tbl_notification_receipt,user_roles,tbl_role,tbl_user");
        recipient = UUID.randomUUID();
        jdbc.update("insert into tbl_user values (?,'ACTIVE',true,null)", recipient);
        role("ROLE_LISTENER");
        reset(badges, websocket, identities, mail, mailSender);
        SecurityContextHolder.clearContext();
    }

    @Test void legacyBusinessRowsAreFilteredBeforeLimitsTotalsRecentAndExplicitTypeFilters() {
        var visible = new ArrayList<UUID>();
        for (int i = 0; i < 12; i++) {
            visible.add(seed(NotificationType.TABLE_JOIN_REQUEST_APPROVED, NOW.minusSeconds(i * 3L + 1)).getId());
            seed(NotificationType.STUDIO_RESERVATION_APPROVED, NOW.minusSeconds(i * 3L));
        }
        var first = service.getUserNotifications(recipient, 0, 5);
        var second = service.getUserNotifications(recipient, 1, 5);
        var last = service.getUserNotifications(recipient, 2, 5);
        assertThat(first.getContent()).extracting(NotificationResponseDto::id).containsExactlyElementsOf(visible.subList(0, 5));
        assertThat(second.getContent()).extracting(NotificationResponseDto::id).containsExactlyElementsOf(visible.subList(5, 10));
        assertThat(last.getContent()).extracting(NotificationResponseDto::id).containsExactlyElementsOf(visible.subList(10, 12));
        assertThat(first.getTotalElements()).isEqualTo(12);
        assertThat(first.hasNext()).isTrue(); assertThat(last.hasNext()).isFalse();
        assertThat(service.getRecentNotifications(recipient)).extracting(NotificationResponseDto::id)
                .containsExactlyElementsOf(visible.subList(0, 10));
        var mixed = service.getUserNotificationsByTypes(recipient,
                List.of(NotificationType.STUDIO_RESERVATION_APPROVED, NotificationType.TABLE_JOIN_REQUEST_APPROVED), 0, 5);
        assertThat(mixed.getTotalElements()).isEqualTo(12);
        assertThat(mixed.getContent()).extracting(NotificationResponseDto::id).containsExactlyElementsOf(visible.subList(0, 5));
        assertThat(service.getUserNotificationsByTypes(recipient, List.of(NotificationType.STUDIO_RESERVATION_APPROVED), 0, 5)).isEmpty();
        assertThat(service.getUnreadCount(recipient)).isEqualTo(12);
        verify(badges).setUnreadWithTtl(recipient, 12);
        assertThat(notifications.count()).isEqualTo(24);
        role("ROLE_MUSICIAN");
        assertThat(service.getUserNotifications(recipient, 0, 5).getTotalElements()).isEqualTo(24);
        assertThat(service.getUnreadCount(recipient)).isEqualTo(24);
        role("ROLE_LISTENER");
        assertThat(service.getUnreadCount(recipient)).isEqualTo(12);
        assertThat(notifications.count()).isEqualTo(24);
    }

    @Test void allCurrentConsumerTypesSurviveWhileEveryBusinessTypeIsHiddenWithoutDeletingHistory() {
        for (NotificationType type : NotificationType.values()) seed(type, NOW.minusSeconds(type.ordinal()));
        List<NotificationType> publicTypes = Arrays.stream(NotificationType.values()).filter(this::consumerType).toList();
        assertThat(service.getUserNotifications(recipient, 0, 100).getContent()).extracting(NotificationResponseDto::type)
                .containsExactlyElementsOf(publicTypes);
        assertThat(service.getUnreadCount(recipient)).isEqualTo(publicTypes.size());
        assertThat(notifications.count()).isEqualTo(NotificationType.values().length);
        assertThat(publicTypes).contains(NotificationType.SOCIAL_NEW_BAND_FOLLOWER, NotificationType.DM_NEW_MESSAGE,
                NotificationType.TABLE_CANCELLED, NotificationType.AUTH_RESET_PASSWORD,
                NotificationType.MEDIA_TRANSCODE_READY, NotificationType.OVERTHINKING_REVEAL_REQUEST_RECEIVED);
    }

    @Test void directLookupMediaResolutionMarkAndClearCannotExposeOrEraseHiddenLegacyRows() {
        var business = seed(NotificationType.COLLAB_APPLICATION_ACCEPTED, NOW);
        var publicItem = seed(NotificationType.SOCIAL_LIKE, NOW.minusSeconds(1));
        assertThat(notifications.findByIdAndRecipientId(business.getId(), recipient)).isEmpty();
        assertThat(notifications.findByIdAndRecipientId(publicItem.getId(), UUID.randomUUID())).isEmpty();
        assertThat(catchThrowableOfType(() -> service.markAsRead(recipient, business.getId()), SoundConnectException.class)
                .getErrorType()).isEqualTo(ErrorType.NOTIFICATION_NOT_FOUND);
        assertThat(catchThrowableOfType(() -> service.deleteById(recipient, business.getId()), SoundConnectException.class)
                .getErrorType()).isEqualTo(ErrorType.NOTIFICATION_NOT_FOUND);
        assertThat(notifications.markAsRead(business.getId(), recipient)).isZero();
        var access = mock(CommentTargetAccessGuard.class);
        var media = mock(MediaAssetRepository.class);
        var mapper = mock(MediaAssetMapper.class);
        var targets = new NotificationMediaTargetService(notifications, access, media, mapper);
        assertThat(catchThrowableOfType(() -> targets.resolve(recipient, business.getId()), SoundConnectException.class)
                .getErrorType()).isEqualTo(ErrorType.NOTIFICATION_NOT_FOUND);
        verifyNoInteractions(access, media, mapper);
        assertThat(service.markAllAsRead(recipient)).isEqualTo(1);
        assertThat(notifications.findById(business.getId()).orElseThrow().isRead()).isFalse();
        assertThat(service.clearAll(recipient)).isEqualTo(1);
        assertThat(notifications.findById(business.getId())).isPresent();
        assertThat(notifications.findById(publicItem.getId())).isEmpty();
        assertThat(service.getUnreadCount(recipient)).isZero();
        verify(websocket, atLeastOnce()).sendUnreadBadgeToUser(recipient, 0);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void brokerAndTransactionalAdmissionUseTheRecipientsDatabaseRoleAndKeepReplayReceipts(boolean throughBroker) throws Exception {
        // A musician sender/authentication must never make a listener recipient eligible.
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("sender", "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_MUSICIAN"))));
        var rejected = new ArrayList<NotificationInboundEvent>();
        try {
            for (NotificationType type : NotificationType.values()) {
                if ("OVERTHINKING".equals(type.getCategory())) continue; // Real source lifecycle has separate PostgreSQL coverage.
                var event = event(type);
                send(event, throughBroker);
                if (!consumerType(type)) rejected.add(event);
            }
        } finally { SecurityContextHolder.clearContext(); }
        drain();
        long publicCount = Arrays.stream(NotificationType.values()).filter(this::consumerType)
                .filter(type -> !"OVERTHINKING".equals(type.getCategory())).count();
        assertThat(notifications.count()).isEqualTo(publicCount);
        assertThat(receipts.count()).isEqualTo(NotificationType.values().length - 3);
        assertThat(rejected).hasSize(33);
        verify(websocket, never()).sendNotificationToUser(any(), argThat(dto -> dto != null && !consumerType(dto.type())));
        role("ROLE_MUSICIAN");
        for (var rejectedEvent : rejected) send(rejectedEvent, throughBroker);
        drain();
        assertThat(notifications.count()).isEqualTo(publicCount);
        assertThat(receipts.count()).isEqualTo(NotificationType.values().length - 3);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void finalDispatchRechecksRoleAfterCommitAndHiddenRowsNeverInflateTheNextBadge(boolean throughBroker) throws Exception {
        role("ROLE_MUSICIAN");
        var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        executor.submit(() -> { started.countDown(); await(release); });
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        var business = event(NotificationType.STUDIO_RESERVATION_APPROVED);
        try {
            send(business, throughBroker);
            assertThat(notifications.findBySourceEventId(business.eventId())).isPresent();
            role("ROLE_LISTENER");
            send(event(NotificationType.TABLE_CANCELLED), throughBroker);
        } finally { release.countDown(); }
        drain();
        verify(websocket, never()).sendNotificationToUser(any(), argThat(dto -> dto != null && dto.type() == business.type()));
        verify(websocket).sendNotificationToUser(eq(recipient), argThat(dto -> dto.type() == NotificationType.TABLE_CANCELLED));
        verify(websocket).sendUnreadBadgeToUser(recipient, 1);
        verify(badges).setUnreadWithTtl(recipient, 1);
        assertThat(notifications.count()).isEqualTo(2);
        assertThat(service.getUnreadCount(recipient)).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings = {"ROLE_MUSICIAN", "ROLE_VENUE", "ROLE_STUDIO"})
    void backstageRecipientsKeepTheirBusinessInboxAndLiveDelivery(String recipientRole) throws Exception {
        role(recipientRole);
        var event = event(NotificationType.COLLAB_APPLICATION_ACCEPTED);
        broker.handle(event); drain();
        assertThat(service.getUserNotifications(recipient, 0, 10).getContent()).extracting(NotificationResponseDto::type)
                .containsExactly(event.type());
        verify(websocket).sendNotificationToUser(eq(recipient), argThat(dto -> dto.type() == event.type()));
        verify(websocket).sendUnreadBadgeToUser(recipient, 1);
    }

    @Test void queuedBusinessMailCannotOutliveAChangeToListener() {
        role("ROLE_MUSICIAN");
        var item = seed(NotificationType.VENUE_APPLICATION_REJECTED, NOW);
        role("ROLE_LISTENER");
        mailDelivery.sendIfCurrent(new MailSendRequest("recipient@example.test", "Private venue workflow", null,
                "Business message", MailKind.NOTIFICATION, Map.of("_notificationId", item.getId().toString())));
        verifyNoInteractions(mailSender);
        assertThat(notifications.findById(item.getId())).isPresent();
    }

    private boolean consumerType(NotificationType type) {
        return Set.of("AUTH", "MEDIA", "SOCIAL", "DM", "TABLE", "OVERTHINKING").contains(type.getCategory());
    }
    private Notification seed(NotificationType type, Instant occurredAt) {
        return notifications.saveAndFlush(Notification.builder().sourceEventId(UUID.randomUUID()).recipientId(recipient)
                .type(type).title(type.getDefaultTitle()).message("Existing inbox body").occurredAt(occurredAt)
                .payload(Map.of()).read(false).build());
    }
    private NotificationInboundEvent event(NotificationType type) {
        return new NotificationInboundEvent(UUID.randomUUID(), recipient, type, type.getDefaultTitle(), "Current event",
                Map.of(), false, NOW);
    }
    private void send(NotificationInboundEvent event, boolean throughBroker) {
        if (throughBroker) broker.handle(event);
        else transaction.executeWithoutResult(status -> transactional.persistInCurrentTransaction(event));
    }
    private void role(String name) {
        transaction.executeWithoutResult(status -> {
            // Domain role writers serialize with the existing account delivery fence.
            jdbc.queryForObject("select id from tbl_user where id=? for update", UUID.class, recipient);
            jdbc.update("delete from user_roles where user_id=?", recipient);
            UUID id = UUID.randomUUID();
            jdbc.update("insert into tbl_role(id,name) values (?,?)", id, name);
            jdbc.update("insert into user_roles(user_id,role_id) values (?,?)", recipient, id);
        });
    }
    private void drain() throws Exception {
        var drained = new CountDownLatch(1); executor.submit(drained::countDown);
        assertThat(drained.await(10, TimeUnit.SECONDS)).isTrue();
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Delivery gate timed out"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
    }
    @Configuration(proxyBeanMethods = false)
    @EntityScan(basePackageClasses = Notification.class)
    @EnableJpaRepositories(basePackageClasses = NotificationRepository.class)
    @Import({NotificationAudienceTestSchema.class, NotificationEventListener.class, NotificationServiceImpl.class,
            TransactionalNotificationService.class, NotificationDeliveryPolicy.class, AccountDeliveryFence.class,
            AfterCommitDeliveryExecutor.class, NotificationMapperImpl.class, NotificationMailDelivery.class})
    static class Config { }
}
