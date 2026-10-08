package com.berkayb.soundconnect.modules.message.dm.service;

import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.notification.repository.NotificationRepository;
import com.berkayb.soundconnect.modules.notification.service.AfterCommitDeliveryExecutor;
import com.berkayb.soundconnect.modules.notification.service.NotificationDeliveryPolicy;
import com.berkayb.soundconnect.modules.user.support.AccountDeliveryFence;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationInboundEvent;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

/** Uses only an explicitly supplied disposable local PostgreSQL database and a unique test schema. */
@EnabledIfSystemProperty(named = "push.test.jdbc-url", matches = "jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):\\d+/.*")
class DmNotificationSourcePostgresTest {
    JdbcTemplate admin, jdbc;
    NamedParameterJdbcTemplate named;
    TransactionTemplate transaction;
    AccountDeliveryFence accounts;
    NotificationDeliveryPolicy policy;
    AfterCommitDeliveryExecutor executor;
    String schema;
    UUID recipient, sender, conversation, message;

    @BeforeEach
    void setup() throws Exception {
        String url = System.getProperty("push.test.jdbc-url");
        String user = System.getProperty("push.test.username", "postgres");
        String password = System.getProperty("push.test.password", "");
        var base = new DriverManagerDataSource(url, user, password);
        try (var connection = base.getConnection()) {
            assertThat(connection.getCatalog()).containsIgnoringCase("test");
        }
        admin = new JdbcTemplate(base);
        schema = "dm_policy_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("create schema " + schema);
        var dataSource = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                user, password);
        jdbc = new JdbcTemplate(dataSource);
        named = new NamedParameterJdbcTemplate(dataSource);
        jdbc.execute("create table tbl_user(id uuid primary key, status text, email_verified boolean, erased_at timestamp)");
        jdbc.execute("create table tbl_dm_conversation(id uuid primary key, user_a_id uuid, user_b_id uuid)");
        jdbc.execute("create table tbl_dm_message(id uuid primary key, conversation_id uuid, sender_id uuid, recipient_id uuid, read_at timestamp, deleted_at timestamp)");
        jdbc.execute("create table tbl_notification(id uuid primary key, recipient_id uuid, type text, payload jsonb, is_read boolean)");
        var manager = new DataSourceTransactionManager(dataSource);
        transaction = new TransactionTemplate(manager);
        accounts = new AccountDeliveryFence(named);
        executor = new AfterCommitDeliveryExecutor();
        policy = new NotificationDeliveryPolicy(accounts, named, manager, executor);
        recipient = UUID.randomUUID(); sender = UUID.randomUUID(); conversation = UUID.randomUUID(); message = UUID.randomUUID();
        jdbc.update("insert into tbl_user values (?,'ACTIVE',true,null),(?,'ACTIVE',true,null)", recipient, sender);
        jdbc.update("insert into tbl_dm_conversation values (?,?,?)", conversation, sender, recipient);
        jdbc.update("insert into tbl_dm_message values (?,?,?,?,null,null)", message, conversation, sender, recipient);
    }

    @AfterEach
    void cleanup() {
        if (executor != null) executor.close();
        if (admin != null && schema != null) admin.execute("drop schema " + schema + " cascade");
    }

    @Test
    void lateUnreadSnapshotCannotPassAfterReadDeletionErasureOrIdentityMismatch() {
        assertThat(eligible(event())).isTrue();
        jdbc.update("update tbl_dm_message set read_at=now() where id=?", message);
        assertThat(eligible(event())).isFalse();
        jdbc.update("update tbl_dm_message set read_at=null,deleted_at=now() where id=?", message);
        assertThat(eligible(event())).isFalse();
        jdbc.update("update tbl_dm_message set deleted_at=null where id=?", message);
        jdbc.update("update tbl_user set erased_at=now() where id=?", sender);
        assertThat(eligible(event())).isFalse();
        jdbc.update("update tbl_user set erased_at=null where id=?", sender);
        var altered = new LinkedHashMap<>(event().payload());
        altered.put("conversationId", UUID.randomUUID().toString());
        assertThat(eligible(withPayload(altered))).isFalse();
        altered = new LinkedHashMap<>(event().payload());
        altered.put("senderId", recipient.toString());
        assertThat(eligible(withPayload(altered))).isFalse();
        altered = new LinkedHashMap<>(event().payload());
        altered.put("recipientId", sender.toString());
        assertThat(eligible(withPayload(altered))).isFalse();
        jdbc.update("delete from tbl_dm_message where id=?", message);
        assertThat(eligible(event())).isFalse();
    }

    @Test
    void admissionBeforeReadIsSerializedSoReadClearsTheJustAdmittedInbox() throws Exception {
        var admitted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var delivery = pool.submit(() -> transaction.executeWithoutResult(status -> {
                assertThat(policy.eligible(event())).isTrue();
                admitted.countDown(); await(release);
                insertNotification(recipient, message);
            }));
            assertThat(admitted.await(5, TimeUnit.SECONDS)).isTrue();
            var reader = pool.submit(() -> transaction.executeWithoutResult(status -> {
                accounts.requireActive(List.of(recipient));
                started.countDown();
                jdbc.queryForObject("select id from tbl_dm_conversation where id=? for update", UUID.class, conversation);
                jdbc.queryForObject("select id from tbl_dm_message where id=? for update", UUID.class, message);
                jdbc.update("update tbl_dm_message set read_at=now() where id=?", message);
                markRead(recipient, message);
            }));
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            try { assertThatThrownBy(() -> reader.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class); }
            finally { release.countDown(); }
            delivery.get(5, TimeUnit.SECONDS);
            reader.get(5, TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForObject("select count(*) from tbl_notification where is_read=false", Long.class)).isZero();
        assertThat(eligible(event())).isFalse();
    }

    @Test
    void readBeforeAdmissionMakesTheWaitingOldEventIneligible() throws Exception {
        var read = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var reader = pool.submit(() -> transaction.executeWithoutResult(status -> {
                accounts.requireActive(List.of(recipient));
                jdbc.queryForObject("select id from tbl_dm_conversation where id=? for update", UUID.class, conversation);
                jdbc.queryForObject("select id from tbl_dm_message where id=? for update", UUID.class, message);
                jdbc.update("update tbl_dm_message set read_at=now() where id=?", message);
                read.countDown(); await(release);
            }));
            assertThat(read.await(5, TimeUnit.SECONDS)).isTrue();
            var delivery = pool.submit(() -> {
                started.countDown();
                return eligible(event());
            });
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            try { assertThatThrownBy(() -> delivery.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class); }
            finally { release.countDown(); }
            reader.get(5, TimeUnit.SECONDS);
            assertThat(delivery.get(5, TimeUnit.SECONDS)).isFalse();
        }
    }

    @Test
    void actualRepositoryUpdateLeavesOtherMessageAndOtherRecipientsUnread() {
        UUID acknowledged = insertNotification(recipient, message);
        UUID otherMessage = insertNotification(recipient, UUID.randomUUID());
        UUID otherRecipient = insertNotification(sender, message);
        assertThat(markRead(recipient, message)).isOne();
        assertThat(jdbc.queryForObject("select is_read from tbl_notification where id=?", Boolean.class, acknowledged)).isTrue();
        assertThat(jdbc.queryForObject("select is_read from tbl_notification where id=?", Boolean.class, otherMessage)).isFalse();
        assertThat(jdbc.queryForObject("select is_read from tbl_notification where id=?", Boolean.class, otherRecipient)).isFalse();
        assertThat(markRead(recipient, message)).isZero();
    }

    @Test
    void missingOrMismatchedConversationCannotDeliverAnOrphanedMessage() {
        jdbc.update("update tbl_dm_conversation set user_a_id=? where id=?", UUID.randomUUID(), conversation);
        assertThat(eligible(event())).isFalse();
        jdbc.update("delete from tbl_dm_conversation where id=?", conversation);
        assertThat(eligible(event())).isFalse();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"INACTIVE", "PENDING_VENUE_REQUEST", "PENDING_STUDIO_REQUEST", "REJECTED_STUDIO_REQUEST"})
    void unavailableSenderOrRecipientCannotCreateNewMessages(String status) {
        for (UUID user : List.of(sender, recipient)) {
            jdbc.update("update tbl_user set status=? where id=?", status, user);
            assertThatThrownBy(() -> transaction.executeWithoutResult(tx -> accounts.requireActive(List.of(sender, recipient))))
                    .isInstanceOf(com.berkayb.soundconnect.shared.exception.SoundConnectException.class);
            jdbc.update("update tbl_user set status='ACTIVE' where id=?", user);
        }
    }

    @Test void unverifiedDeletedAndMissingPeerAreRejectedButErasedPeerHistoryRemainsReadable() {
        jdbc.update("update tbl_user set email_verified=false where id=?", sender);
        assertThatThrownBy(() -> transaction.executeWithoutResult(tx -> accounts.requireActive(List.of(sender, recipient))))
                .isInstanceOf(com.berkayb.soundconnect.shared.exception.SoundConnectException.class);
        jdbc.update("update tbl_user set email_verified=true,erased_at=now() where id=?", sender);
        assertThatThrownBy(() -> transaction.executeWithoutResult(tx -> accounts.requireActive(List.of(sender, recipient))))
                .isInstanceOf(com.berkayb.soundconnect.shared.exception.SoundConnectException.class);
        transaction.executeWithoutResult(tx -> accounts.requireConversationReader(recipient, List.of(sender, recipient)));
        assertThat(eligible(event())).isFalse();
        assertThatThrownBy(() -> transaction.executeWithoutResult(tx -> accounts.requireActive(List.of(UUID.randomUUID(), recipient))))
                .isInstanceOf(com.berkayb.soundconnect.shared.exception.SoundConnectException.class);
    }

    @Test
    void conversationDeletionWaitsForAdmissionAndThenRemovesItsInbox() throws Exception {
        var admitted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var delivery = pool.submit(() -> transaction.executeWithoutResult(status -> {
                assertThat(policy.eligible(event())).isTrue();
                admitted.countDown(); await(release);
                insertNotification(recipient, message);
            }));
            assertThat(admitted.await(5, TimeUnit.SECONDS)).isTrue();
            var deletion = pool.submit(() -> transaction.executeWithoutResult(status -> {
                started.countDown();
                jdbc.queryForObject("select id from tbl_dm_conversation where id=? for update", UUID.class, conversation);
                try {
                    String sql = NotificationRepository.class.getMethod("deleteDmConversationNotifications", String.class)
                            .getAnnotation(Query.class).value();
                    named.update(sql, Map.of("conversationId", conversation.toString()));
                } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
                jdbc.update("delete from tbl_dm_message where conversation_id=?", conversation);
                jdbc.update("delete from tbl_dm_conversation where id=?", conversation);
            }));
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            try { assertThatThrownBy(() -> deletion.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class); }
            finally { release.countDown(); }
            delivery.get(5, TimeUnit.SECONDS);
            deletion.get(5, TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForObject("select count(*) from tbl_notification", Long.class)).isZero();
        assertThat(eligible(event())).isFalse();
    }

    private int markRead(UUID user, UUID messageId) {
        try {
            String sql = NotificationRepository.class.getMethod("markUnreadDmNotificationsAsReadByMessage", UUID.class, String.class)
                    .getAnnotation(Query.class).value();
            return named.update(sql, Map.of("recipientId", user, "messageId", messageId.toString()));
        } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }

    private UUID insertNotification(UUID user, UUID messageId) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into tbl_notification values (?,?,'DM_NEW_MESSAGE',?::jsonb,false)", id, user,
                "{\"messageId\":\"" + messageId + "\",\"conversationId\":\"" + conversation + "\"}");
        return id;
    }

    private boolean eligible(NotificationInboundEvent event) {
        return Boolean.TRUE.equals(transaction.execute(status -> policy.eligible(event)));
    }

    private NotificationInboundEvent event() {
        return withPayload(Map.of("messageId", message.toString(), "conversationId", conversation.toString(),
                "senderId", sender.toString(), "recipientId", recipient.toString()));
    }

    private NotificationInboundEvent withPayload(Map<String,Object> payload) {
        return new NotificationInboundEvent(UUID.randomUUID(), recipient, NotificationType.DM_NEW_MESSAGE,
                "New message", "hello", payload, false, Instant.now());
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Test latch timed out"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
    }
}
