package com.berkayb.soundconnect.modules.notification.service;

import com.berkayb.soundconnect.modules.notification.dto.response.NotificationResponseDto;
import com.berkayb.soundconnect.modules.notification.entity.Notification;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.notification.helper.NotificationBadgeCacheHelper;
import com.berkayb.soundconnect.modules.notification.listener.NotificationEventListener;
import com.berkayb.soundconnect.modules.notification.mapper.NotificationMapper;
import com.berkayb.soundconnect.modules.notification.repository.NotificationReceiptRepository;
import com.berkayb.soundconnect.modules.notification.repository.NotificationRepository;
import com.berkayb.soundconnect.modules.notification.websocket.NotificationWebSocketService;
import com.berkayb.soundconnect.modules.profile.shared.identity.GhostListenerIdentityBatchResolver;
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
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Real unique-index/transaction behavior on a disposable database, never the development datasource. */
@Testcontainers
@DataJpaTest(properties = {
        "spring.config.import=", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.hikari.connection-init-sql=",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ContextConfiguration(classes = NotificationReplayReceiptPostgresTest.TestConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class NotificationReplayReceiptPostgresTest {
    @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4")
            .withDatabaseName("notification_replay_receipts_test")
            .withUsername("receipt_test").withPassword("receipt_test");

    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", postgres::getJdbcUrl);
        properties.add("spring.datasource.username", postgres::getUsername);
        properties.add("spring.datasource.password", postgres::getPassword);
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired NotificationRepository notifications;
    @Autowired NotificationReceiptRepository receipts;
    @Autowired NotificationEventListener listener;
    @Autowired NotificationService service;
    @Autowired TransactionalNotificationService transactionalNotifications;
    @Autowired NotificationCleanupService cleanup;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean NotificationMapper mapper;
    @MockitoBean NotificationBadgeCacheHelper badges;
    @MockitoBean NotificationWebSocketService websocket;
    @MockitoBean MailProducer mail;
    @MockitoBean GhostListenerIdentityBatchResolver identityResolver;
    TransactionTemplate transactions;

    @BeforeEach void setup() {
        transactions = new TransactionTemplate(transactionManager);
        transactions.executeWithoutResult(status -> {
            notifications.deleteAll();
            receipts.deleteAll();
        });
        reset(mapper, badges, websocket, mail, identityResolver);
        when(mapper.toDto(any(Notification.class))).thenAnswer(call -> {
            Notification item = call.getArgument(0);
            return new NotificationResponseDto(item.getId(), item.getRecipientId(), item.getType(), item.getTitle(),
                    item.getMessage(), item.isRead(), item.getOccurredAt(), item.getPayload());
        });
    }

    @Test void deletedNotificationCannotBeRecreatedByRabbitOrTransactionalReplay() {
        NotificationInboundEvent event = event(UUID.randomUUID());
        listener.handle(event);
        Notification stored = notifications.findBySourceEventId(event.eventId()).orElseThrow();

        service.deleteById(event.recipientId(), stored.getId());
        listener.handle(event);
        transactions.executeWithoutResult(status -> transactionalNotifications.persistInCurrentTransaction(event));

        assertThat(notifications.count()).isZero();
        assertThat(receipts.count()).isEqualTo(1);
        verify(websocket).sendNotificationToUser(eq(event.recipientId()), any());
        verifyNoInteractions(mail);
    }

    @Test void clearAllPreservesReceiptsForOnlyTheRecipientsDeletedInbox() {
        UUID recipient = UUID.randomUUID();
        NotificationInboundEvent first = event(recipient), second = event(recipient), other = event(UUID.randomUUID());
        listener.handle(first);
        listener.handle(second);
        listener.handle(other);

        assertThat(service.clearAll(recipient)).isEqualTo(2);
        listener.handle(first);
        listener.handle(second);

        assertThat(notifications.count()).isEqualTo(1);
        assertThat(notifications.findBySourceEventId(other.eventId())).isPresent();
        assertThat(receipts.count()).isEqualTo(3);
        verify(websocket, times(2)).sendNotificationToUser(eq(recipient), any());
    }

    @Test void failedDomainTransactionRollsBackItsReceiptAndCanRetry() {
        NotificationInboundEvent event = event(UUID.randomUUID());
        transactions.executeWithoutResult(status -> {
            transactionalNotifications.persistInCurrentTransaction(event);
            status.setRollbackOnly();
        });
        assertThat(notifications.count()).isZero();
        assertThat(receipts.count()).isZero();
        verifyNoInteractions(websocket);

        listener.handle(event);

        assertThat(notifications.count()).isEqualTo(1);
        assertThat(receipts.count()).isEqualTo(1);
        verify(websocket).sendNotificationToUser(eq(event.recipientId()), any());
    }

    @Test void failureInLaterFanoutRecipientRollsBackEarlierInboxAndReceiptTogether() {
        NotificationInboundEvent first = event(UUID.randomUUID()), second = event(UUID.randomUUID());
        NotificationInboundEvent invalid = new NotificationInboundEvent(second.eventId(), second.recipientId(), second.type(),
                "x".repeat(161), second.message(), second.payload(), false, second.occurredAt());

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            transactionalNotifications.persistInCurrentTransaction(first);
            transactionalNotifications.persistInCurrentTransaction(invalid);
        })).isInstanceOf(IllegalArgumentException.class);

        assertThat(notifications.count()).isZero();
        assertThat(receipts.count()).isZero();
        verifyNoInteractions(websocket, badges);
    }

    @Test void simultaneousDeliveriesClaimOneReceiptAndPushOnlyAfterTheWinningCommit() throws Exception {
        NotificationInboundEvent event = event(UUID.randomUUID());
        CountDownLatch staged = new CountDownLatch(1), release = new CountDownLatch(1), secondEntered = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var first = workers.submit(() -> transactions.executeWithoutResult(status -> {
                listener.handle(event);
                staged.countDown();
                await(release);
            }));
            await(staged);
            verifyNoInteractions(websocket);
            var second = workers.submit(() -> {
                secondEntered.countDown();
                listener.handle(event);
            });
            await(secondEntered);
            assertThatThrownBy(() -> second.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(notifications.count()).isEqualTo(1);
        assertThat(receipts.count()).isEqualTo(1);
        verify(websocket).sendNotificationToUser(eq(event.recipientId()), any());
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void legacyDeletionAndReplayRaceIsAtomicWhetherDeletionCommitsOrRollsBack(boolean commitDelete) throws Exception {
        NotificationInboundEvent event = event(UUID.randomUUID());
        Notification legacy = seedLegacy(event);
        assertThat(receipts.count()).isZero();
        CountDownLatch staged = new CountDownLatch(1), release = new CountDownLatch(1), replayEntered = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var deletion = workers.submit(() -> transactions.executeWithoutResult(status -> {
                service.deleteById(event.recipientId(), legacy.getId());
                staged.countDown();
                await(release);
                if (!commitDelete) status.setRollbackOnly();
            }));
            await(staged);
            var replay = workers.submit(() -> {
                replayEntered.countDown();
                listener.handle(event);
            });
            await(replayEntered);
            assertThatThrownBy(() -> replay.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            deletion.get(10, TimeUnit.SECONDS);
            replay.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(notifications.count()).isEqualTo(commitDelete ? 0 : 1);
        assertThat(receipts.count()).isEqualTo(1);
        verify(websocket, never()).sendNotificationToUser(any(), any());
    }

    @Test void foreignRecipientCannotDeleteOrWriteAReceiptForSomeoneElsesInbox() {
        NotificationInboundEvent event = event(UUID.randomUUID());
        Notification legacy = seedLegacy(event);

        assertThatThrownBy(() -> service.deleteById(UUID.randomUUID(), legacy.getId()))
                .isInstanceOf(com.berkayb.soundconnect.shared.exception.SoundConnectException.class);

        assertThat(notifications.count()).isEqualTo(1);
        assertThat(receipts.count()).isZero();
        verifyNoInteractions(websocket);
    }

    @Test void additiveMigrationBackfillsOnlyTechnicalKnownIdsAndIsRerunnable() throws Exception {
        NotificationInboundEvent identified = event(UUID.randomUUID());
        seedLegacy(identified);
        seedLegacy(new NotificationInboundEvent(null, UUID.randomUUID(), identified.type(), "Legacy no ID", "private body",
                Map.of("avatarUrl", "private image"), false, identified.occurredAt()));
        String migration = Files.readString(Path.of("scripts/db/2026-09-07-notification-replay-receipts.sql"));

        jdbc.execute(migration);
        Instant recordedAt = receipts.findById(identified.eventId()).orElseThrow().getRecordedAt();
        jdbc.execute(migration);

        assertThat(notifications.count()).isEqualTo(2);
        assertThat(receipts.count()).isEqualTo(1);
        assertThat(receipts.findById(identified.eventId()).orElseThrow().getRecordedAt()).isEqualTo(recordedAt);
        assertThat(jdbc.queryForList("select column_name from information_schema.columns where table_name='tbl_notification_receipt' order by column_name", String.class))
                .containsExactly("recipient_id", "recorded_at", "source_event_id");
        assertThat(jdbc.queryForObject("select count(*) from soundconnect_schema_migrations where migration_id='2026-09-07-notification-replay-receipts'", Integer.class)).isEqualTo(1);
        assertThat(Files.readString(Path.of("scripts/dev.ps1"))).contains("2026-09-07-notification-replay-receipts.sql");
    }

    @Test void legacyNullSourceCanStillBeDeletedWithoutInventingAnIdentity() {
        NotificationInboundEvent event = event(UUID.randomUUID());
        Notification legacy = seedLegacy(new NotificationInboundEvent(null, event.recipientId(), event.type(),
                event.title(), event.message(), event.payload(), false, event.occurredAt()));

        service.deleteById(event.recipientId(), legacy.getId());
        assertThatThrownBy(() -> listener.handle(new NotificationInboundEvent(null, event.recipientId(), event.type(),
                event.title(), event.message(), event.payload(), false, event.occurredAt())))
                .isInstanceOf(org.springframework.amqp.AmqpRejectAndDontRequeueException.class);

        assertThat(notifications.count()).isZero();
        assertThat(receipts.count()).isZero();
        verify(websocket, never()).sendNotificationToUser(any(), any());
    }

    @Test void retentionCleanupKeepsLegacyReplayReceiptsAfterErasingExpiredContent() {
        NotificationInboundEvent event = event(UUID.randomUUID());
        Notification legacy = seedLegacy(event);
        jdbc.update("update tbl_notification set created_at = now() - interval '40 days' where id = ?", legacy.getId());

        cleanup.cleanupExpiredNotifications();
        listener.handle(event);

        assertThat(notifications.count()).isZero();
        assertThat(receipts.count()).isEqualTo(1);
        verify(websocket, never()).sendNotificationToUser(any(), any());
    }

    @Test void duplicateSourceIdWithDifferentRecipientCannotCopyOrReplaceOriginalInbox() {
        NotificationInboundEvent original = event(UUID.randomUUID());
        listener.handle(original);
        NotificationInboundEvent conflicting = new NotificationInboundEvent(original.eventId(), UUID.randomUUID(), original.type(),
                "Conflicting title", "Conflicting body", Map.of(), false, original.occurredAt());

        listener.handle(conflicting);

        Notification stored = notifications.findBySourceEventId(original.eventId()).orElseThrow();
        assertThat(stored.getRecipientId()).isEqualTo(original.recipientId());
        assertThat(stored.getTitle()).isEqualTo(original.title());
        assertThat(receipts.findById(original.eventId()).orElseThrow().getRecipientId()).isEqualTo(original.recipientId());
        assertThat(notifications.count()).isEqualTo(1);
        verify(websocket, never()).sendNotificationToUser(eq(conflicting.recipientId()), any());
    }

    private Notification seedLegacy(NotificationInboundEvent event) {
        return notifications.saveAndFlush(Notification.builder().sourceEventId(event.eventId()).recipientId(event.recipientId())
                .type(event.type()).title(event.title()).message(event.message()).payload(event.payload())
                .occurredAt(event.occurredAt()).read(false).build());
    }

    private static NotificationInboundEvent event(UUID recipientId) {
        return NotificationInboundEvent.builder().eventId(UUID.randomUUID()).recipientId(recipientId)
                .type(NotificationType.BAND_INVITE_RECEIVED).title("Group invitation").message("Invitation received")
                .payload(Map.of("module", "BAND", "bandId", UUID.randomUUID().toString()))
                .emailForce(false).occurredAt(Instant.parse("2026-09-07T09:00:00Z")).build();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Timed out waiting for transaction stage");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EntityScan(basePackageClasses = Notification.class)
    @EnableJpaRepositories(basePackageClasses = NotificationRepository.class)
    @Import({NotificationEventListener.class, NotificationServiceImpl.class, TransactionalNotificationService.class, NotificationCleanupService.class,
            com.berkayb.soundconnect.support.DeliveryPolicyTestSupport.Config.class})
    static class TestConfiguration { }
}
