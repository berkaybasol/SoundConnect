package com.berkayb.soundconnect.modules.message.dm.service;

import com.berkayb.soundconnect.modules.message.dm.abuse.DmRateLimitGuard;
import com.berkayb.soundconnect.modules.message.dm.dto.request.DMMessageRequestDto;
import com.berkayb.soundconnect.modules.message.dm.entity.DMConversation;
import com.berkayb.soundconnect.modules.message.dm.event.DmMessageEventPublisher;
import com.berkayb.soundconnect.modules.message.dm.mapper.DMMessageMapperImpl;
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
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfSystemProperty(named = "push.test.jdbc-url", matches = "jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):\\d+/.*")
@DataJpaTest(properties = {"spring.config.import=", "app.notification.push.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.datasource.hikari.connection-init-sql=",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ContextConfiguration(classes = DmSendReliabilityPostgresIT.Config.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DmSendReliabilityPostgresIT {
    static final String SCHEMA = "dm_send_" + UUID.randomUUID().toString().replace("-", "");
    static JdbcTemplate admin;
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) throws Exception {
        String url = System.getProperty("push.test.jdbc-url"), username = System.getProperty("push.test.username", "postgres");
        String password = System.getProperty("push.test.password", "");
        var source = new DriverManagerDataSource(url, username, password);
        try (var connection = source.getConnection()) { assertThat(connection.getCatalog()).containsIgnoringCase("test"); }
        admin = new JdbcTemplate(source); admin.execute("create schema " + SCHEMA);
        properties.add("spring.datasource.url", () -> url + (url.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA);
        properties.add("spring.datasource.username", () -> username); properties.add("spring.datasource.password", () -> password);
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }
    @AfterAll static void cleanup() { if (admin != null) admin.execute("drop schema " + SCHEMA + " cascade"); }
    @Autowired DMMessageServiceImpl service;
    @Autowired DmModerationService moderation;
    @Autowired DMMessageRepository messages;
    @Autowired DMConversationRepository conversations;
    @Autowired NotificationRepository notifications;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @MockitoBean DmRateLimitGuard rateLimit;
    @MockitoBean DmMessageEventPublisher events;
    @MockitoBean UserRepository users;
    @MockitoBean PublicProfileResolverService profiles;
    @MockitoBean NotificationMapper notificationMapper;
    @MockitoBean NotificationService notificationService;
    @MockitoBean NotificationBadgeCacheHelper badges;
    @MockitoBean NotificationWebSocketService websocket;
    UUID sender, recipient, conversation, key;

    @BeforeEach void setup() throws Exception {
        // Execute the exact replayable migration in this disposable schema, twice.
        String migration = Files.readString(Path.of("scripts/db/2026-09-23-dm-send-reliability.sql"));
        jdbc.execute(migration); jdbc.execute(migration);
        jdbc.execute("create table if not exists tbl_user(id uuid primary key, status text, email_verified boolean, erased_at timestamp)");
        notifications.deleteAll(); messages.deleteAll(); conversations.deleteAll();
        jdbc.update("delete from tbl_dm_send_receipt"); jdbc.update("delete from tbl_notification_receipt"); jdbc.update("delete from tbl_user");
        sender = UUID.randomUUID(); recipient = UUID.randomUUID(); key = UUID.randomUUID();
        jdbc.update("insert into tbl_user values (?,'ACTIVE',true,null),(?,'ACTIVE',true,null)", sender, recipient);
        conversation = conversations.saveAndFlush(DMConversation.builder().userAId(sender).userBId(recipient).build()).getId();
        when(profiles.resolveByUserId(sender)).thenReturn(new UserProfilesResolveResponseDto(sender,
                List.of(new UserProfileTargetDto("MUSICIAN", UUID.randomUUID(), "Fixture", null))));
    }

    @Test void committedRetryReturnsSameMessageAndDoesNotConsumeQuotaOrEmitAnotherNotification() {
        var first = service.sendMessage(request("hello"), sender);
        doThrow(new IllegalStateException("Redis unavailable")).when(rateLimit).check(sender, recipient);
        var replay = service.sendMessage(request("hello"), sender);
        assertThat(replay.messageId()).isEqualTo(first.messageId());
        assertCounts(1); verify(rateLimit, times(1)).check(sender, recipient);
        verify(events, times(1)).publishMessageSentEvent(any());
    }

    @Test void concurrentSameKeyAcrossTwoTransactionsHasOneDurableOutcome() throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<UUID> send = () -> { start.await(5, TimeUnit.SECONDS); return service.sendMessage(request("parallel"), sender).messageId(); };
            var first = pool.submit(send); var second = pool.submit(send); start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(second.get(10, TimeUnit.SECONDS));
        }
        assertCounts(1); verify(rateLimit, times(1)).check(sender, recipient);
    }

    @Test void reusingAKeyWithDifferentContentOrRecipientConversationFailsWithoutAnotherWrite() {
        service.sendMessage(request("original"), sender);
        assertConflict(request("changed"));
        UUID other = UUID.randomUUID(); jdbc.update("insert into tbl_user values (?,'ACTIVE',true,null)", other);
        UUID otherConversation = conversations.saveAndFlush(DMConversation.builder().userAId(sender).userBId(other).build()).getId();
        assertConflict(new DMMessageRequestDto(otherConversation, other, "original", "text", key));
        assertCounts(1); assertThat(messages.findAll().getFirst().getContent()).isEqualTo("original");
    }

    @Test void concurrentCrossConversationKeyReuseHasOneWinnerAndOneConflict() throws Exception {
        UUID other = UUID.randomUUID(); jdbc.update("insert into tbl_user values (?,'ACTIVE',true,null)", other);
        UUID otherConversation = conversations.saveAndFlush(DMConversation.builder().userAId(sender).userBId(other).build()).getId();
        var otherRequest = new DMMessageRequestDto(otherConversation, other, "different route", "text", key);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var futures = List.of(pool.submit(() -> sendAfter(start, request("original"))),
                    pool.submit(() -> sendAfter(start, otherRequest)));
            start.countDown();
            assertThat(List.of(futures.get(0).get(10,TimeUnit.SECONDS), futures.get(1).get(10,TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("accepted", "conflict");
        }
        assertCounts(1);
    }

    @Test void newSendCannotCreateReceiptMessageOrInboxWhenRateProtectionIsUnavailable() {
        doThrow(new com.berkayb.soundconnect.shared.exception.ServiceUnavailableRetryException(ErrorType.DM_RATE_LIMIT_UNAVAILABLE, 5))
                .when(rateLimit).check(sender, recipient);
        assertThatThrownBy(() -> service.sendMessage(request("blocked"), sender))
                .isInstanceOf(com.berkayb.soundconnect.shared.exception.ServiceUnavailableRetryException.class);
        assertCounts(0); verifyNoInteractions(events);
    }

    @Test void receiptDoesNotPreventErasureAndErasedAccountCannotUseItsPriorKey() {
        service.sendMessage(request("history"), sender);
        jdbc.update("update tbl_user set erased_at=now(),status='INACTIVE',email_verified=false where id=?", sender);
        assertThat(catchThrowableOfType(() -> service.sendMessage(request("history"), sender), SoundConnectException.class).getErrorType())
                .isEqualTo(ErrorType.ACCOUNT_DELETED);
        // Content-free keys intentionally have no user FK; even physical fixture
        // deletion cannot be blocked by a replay tombstone.
        assertThat(jdbc.update("delete from tbl_user where id=?", sender)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from tbl_dm_send_receipt",Long.class)).isOne();
    }

    @Test void rollbackReleasesTheReceiptAndAllowsTheSameLogicalSendToSucceedLater() {
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            service.sendMessage(request("rollback"), sender); assertCounts(1); status.setRollbackOnly();
        });
        assertCounts(0);
        service.sendMessage(request("rollback"), sender); assertCounts(1);
    }

    @Test void moderationCannotReleaseACommittedKeyAndResurrectDeletedMessageContent() {
        UUID message = service.sendMessage(request("removed"), sender).messageId();
        moderation.deleteMessage(conversation, message);
        assertThat(catchThrowableOfType(() -> service.sendMessage(request("removed"), sender), SoundConnectException.class).getErrorType())
                .isEqualTo(ErrorType.MESSAGE_NOT_FOUND);
        assertThat(messages.count()).isZero(); assertThat(notifications.count()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from tbl_dm_send_receipt", Long.class)).isOne();
        verify(rateLimit, times(1)).check(sender, recipient);
    }

    @Test void legacyRequestsRemainAcceptedButEveryUnkeyedPostIsADistinctSend() {
        var legacy = new DMMessageRequestDto(conversation, recipient, "legacy", "text");
        assertThat(service.sendMessage(legacy, sender).messageId()).isNotEqualTo(service.sendMessage(legacy, sender).messageId());
        assertThat(messages.count()).isEqualTo(2); assertThat(notifications.count()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from tbl_dm_send_receipt", Long.class)).isZero();
    }

    private void assertConflict(DMMessageRequestDto request) {
        assertThat(catchThrowableOfType(() -> service.sendMessage(request, sender), SoundConnectException.class).getErrorType())
                .isEqualTo(ErrorType.DM_MESSAGE_IDEMPOTENCY_CONFLICT);
    }
    private String sendAfter(CountDownLatch start, DMMessageRequestDto request) throws InterruptedException {
        start.await(5,TimeUnit.SECONDS);
        try { service.sendMessage(request,sender); return "accepted"; }
        catch (SoundConnectException failure) {
            assertThat(failure.getErrorType()).isEqualTo(ErrorType.DM_MESSAGE_IDEMPOTENCY_CONFLICT); return "conflict";
        }
    }
    private DMMessageRequestDto request(String content) { return new DMMessageRequestDto(conversation, recipient, content, "text", key); }
    private void assertCounts(long count) {
        assertThat(messages.count()).isEqualTo(count); assertThat(notifications.count()).isEqualTo(count);
        assertThat(jdbc.queryForObject("select count(*) from tbl_dm_send_receipt", Long.class)).isEqualTo(count);
    }
    @Configuration(proxyBeanMethods = false)
    @EntityScan(basePackageClasses = {DMConversation.class, Notification.class})
    @EnableJpaRepositories(basePackageClasses = {DMConversationRepository.class, NotificationRepository.class})
    @EnableJpaAuditing
    @Import({DMMessageServiceImpl.class, DmSendReceiptStore.class, DmNotificationService.class, DmModerationService.class,
            DMMessageMapperImpl.class, AccountDeliveryFence.class, TransactionalNotificationService.class,
            com.berkayb.soundconnect.support.DeliveryPolicyTestSupport.Config.class,
            com.berkayb.soundconnect.modules.notification.support.NotificationAudienceTestSchema.class})
    static class Config { }
}
