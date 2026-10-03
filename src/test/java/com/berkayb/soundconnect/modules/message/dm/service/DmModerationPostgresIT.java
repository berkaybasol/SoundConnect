package com.berkayb.soundconnect.modules.message.dm.service;

import com.berkayb.soundconnect.modules.message.dm.entity.DMConversation;
import com.berkayb.soundconnect.modules.message.dm.entity.DMMessage;
import com.berkayb.soundconnect.modules.message.dm.repository.DMConversationRepository;
import com.berkayb.soundconnect.modules.message.dm.repository.DMMessageRepository;
import com.berkayb.soundconnect.modules.notification.entity.Notification;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.notification.repository.NotificationRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/** Full repository/service transactions in a fresh schema of an explicitly supplied disposable DB. */
@EnabledIfSystemProperty(named = "push.test.jdbc-url", matches = "jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):\\d+/.*")
@DataJpaTest(properties = {"spring.config.import=", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.hikari.connection-init-sql=", "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ContextConfiguration(classes = DmModerationPostgresIT.Config.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DmModerationPostgresIT {
    static final String SCHEMA = "dm_moderation_" + UUID.randomUUID().toString().replace("-", "");
    static JdbcTemplate admin;
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) throws Exception {
        String url = System.getProperty("push.test.jdbc-url");
        String username = System.getProperty("push.test.username", "postgres");
        String password = System.getProperty("push.test.password", "");
        var source = new DriverManagerDataSource(url, username, password);
        try (var connection = source.getConnection()) { assertThat(connection.getCatalog()).containsIgnoringCase("test"); }
        admin = new JdbcTemplate(source);
        admin.execute("create schema " + SCHEMA);
        properties.add("spring.datasource.url", () -> url + (url.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA);
        properties.add("spring.datasource.username", () -> username);
        properties.add("spring.datasource.password", () -> password);
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }
    @AfterAll static void dropSchema() { if (admin != null) admin.execute("drop schema " + SCHEMA + " cascade"); }
    @Autowired DmModerationService moderation;
    @Autowired DMMessageRepository messages;
    @Autowired DMConversationRepository conversations;
    @Autowired NotificationRepository notifications;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    UUID sender, recipient;
    DMConversation conversation;

    @BeforeEach void setup() {
        // The real push FK has this same ON DELETE CASCADE contract. No provider/worker starts here.
        jdbc.execute("create table if not exists fixture_push_job(id uuid primary key, notification_id uuid not null references tbl_notification(id) on delete cascade)");
        notifications.deleteAll(); messages.deleteAll(); conversations.deleteAll();
        jdbc.update("delete from tbl_notification_receipt");
        sender = UUID.randomUUID(); recipient = UUID.randomUUID();
        conversation = conversations.saveAndFlush(DMConversation.builder().userAId(sender).userBId(recipient).build());
    }

    @Test void deletingOneMessageRemovesOnlyItsInboxAndJobAndRecomputesPreview() {
        var first = message(conversation, "first");
        var last = message(conversation, "last");
        UUID firstInbox = notification(first), lastInbox = notification(last);
        moderation.deleteMessage(conversation.getId(), last.getId());
        assertThat(messages.existsById(last.getId())).isFalse();
        assertThat(notifications.existsById(lastInbox)).isFalse();
        assertThat(notifications.existsById(firstInbox)).isTrue();
        assertThat(messages.countByRecipientIdAndReadAtIsNull(recipient)).isOne();
        assertThat(conversations.findById(conversation.getId()).orElseThrow().getLastMessageAt())
                .isEqualTo(messages.findById(first.getId()).orElseThrow().getCreatedAt());
        assertThat(jdbc.queryForObject("select count(*) from fixture_push_job", Long.class)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from tbl_notification_receipt", Long.class)).isEqualTo(2);
    }

    @Test void deletingConversationRemovesBothDirectionsAndNotificationsButPreservesOtherConversation() {
        var first = message(conversation, "forward");
        var reverse = message(conversation, "reverse");
        reverse.setSenderId(recipient); reverse.setRecipientId(sender); messages.saveAndFlush(reverse);
        notification(first); notification(reverse);
        var other = conversations.saveAndFlush(DMConversation.builder().userAId(sender).userBId(UUID.randomUUID()).build());
        var retained = message(other, "retained");
        UUID retainedInbox = notification(retained);
        moderation.deleteConversation(conversation.getId());
        assertThat(conversations.existsById(conversation.getId())).isFalse();
        assertThat(messages.findAll()).extracting(DMMessage::getId).containsExactly(retained.getId());
        assertThat(notifications.findAll()).extracting(Notification::getId).containsExactly(retainedInbox);
        assertThat(jdbc.queryForObject("select count(*) from fixture_push_job", Long.class)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from tbl_notification_receipt", Long.class)).isEqualTo(3);
    }

    @Test void rollbackRestoresSourceInboxAndCascadedJobTogether() {
        var message = message(conversation, "rollback");
        UUID inbox = notification(message);
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            moderation.deleteConversation(conversation.getId());
            assertThat(messages.count()).isZero(); assertThat(notifications.count()).isZero();
            status.setRollbackOnly();
        });
        assertThat(conversations.existsById(conversation.getId())).isTrue();
        assertThat(messages.existsById(message.getId())).isTrue();
        assertThat(notifications.existsById(inbox)).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from fixture_push_job", Long.class)).isOne();
    }

    @Test void deletedAndLegacyOrphanMessagesNeverInflateUnreadAndDeletedContentIsHidden() {
        var visible = message(conversation, "visible");
        var deleted = message(conversation, "deleted");
        deleted.setDeletedAt(LocalDateTime.now()); messages.saveAndFlush(deleted);
        var orphan = message(conversation, "orphan");
        orphan.setConversationId(UUID.randomUUID()); messages.saveAndFlush(orphan);
        assertThat(messages.countByRecipientIdAndReadAtIsNull(recipient)).isOne();
        assertThat(messages.findByRecipientIdAndReadAtIsNull(recipient)).extracting(DMMessage::getId).containsExactly(visible.getId());
        assertThat(messages.findByConversationId(conversation.getId(), PageRequest.of(0, 30)).getContent())
                .extracting(DMMessage::getId).containsExactly(visible.getId());
        assertThat(messages.findTopByConversationIdOrderByCreatedAtDesc(conversation.getId()).orElseThrow().getId()).isEqualTo(visible.getId());
    }

    @Test void messageFromAnotherConversationCannotBeDeletedThroughTheWrongParent() {
        var message = message(conversation, "protected");
        UUID inbox = notification(message);
        var other = conversations.saveAndFlush(DMConversation.builder().userAId(sender).userBId(UUID.randomUUID()).build());
        assertThatThrownBy(() -> moderation.deleteMessage(other.getId(), message.getId()))
                .isInstanceOf(com.berkayb.soundconnect.shared.exception.SoundConnectException.class);
        assertThat(messages.existsById(message.getId())).isTrue();
        assertThat(notifications.existsById(inbox)).isTrue();
    }

    private DMMessage message(DMConversation parent, String content) {
        return messages.saveAndFlush(DMMessage.builder().conversationId(parent.getId()).senderId(sender)
                .recipientId(recipient).content(content).messageType("text").build());
    }
    private UUID notification(DMMessage message) {
        var row = notifications.saveAndFlush(Notification.builder().recipientId(message.getRecipientId())
                .sourceEventId(DmNotificationService.eventId(message.getId(), message.getRecipientId()))
                .type(NotificationType.DM_NEW_MESSAGE).title("Fixture").message("Fixture").occurredAt(java.time.Instant.now())
                .payload(Map.of("messageId", message.getId().toString(), "conversationId", message.getConversationId().toString())).build());
        jdbc.update("insert into fixture_push_job values (?,?)", UUID.randomUUID(), row.getId());
        jdbc.update("insert into tbl_notification_receipt(source_event_id,recipient_id,recorded_at) values (?,?,current_timestamp)",
                row.getSourceEventId(), row.getRecipientId());
        return row.getId();
    }
    @Configuration(proxyBeanMethods = false)
    @EntityScan(basePackageClasses = {DMConversation.class, Notification.class})
    @EnableJpaRepositories(basePackageClasses = {DMConversationRepository.class, NotificationRepository.class})
    @EnableJpaAuditing
    @Import(DmModerationService.class)
    static class Config { }
}
