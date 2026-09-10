package com.berkayb.soundconnect.modules.overthinking.outbox;

import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationInboundEvent;
import com.berkayb.soundconnect.modules.notification.entity.NotificationReceipt;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationProducer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Transaction/lease/SQL guarantees against an explicitly verified disposable database. */
@Testcontainers
@DataJpaTest(properties = {
        "spring.config.location=classpath:/application-test.yml", "spring.config.import=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@ContextConfiguration(classes = OverthinkingNotificationOutboxPostgresTest.Config.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OverthinkingNotificationOutboxPostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("overthinking_outbox_test").withUsername("outbox_test")
            .withPassword("outbox_test").withReuse(false);

    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired OverthinkingNotificationOutboxRepository repository;
    @Autowired OverthinkingNotificationOutboxService service;
    @Autowired OverthinkingNotificationOutboxPublisher publisher;
    @Autowired OverthinkingNotificationOutboxDispatcher dispatcher;
    @Autowired OverthinkingNotificationOutboxProperties properties;
    @Autowired NotificationProducer producer;
    @Autowired MutableClock clock;
    @Autowired QueuedExecutor executor;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    TransactionTemplate transactions;

    @BeforeEach void setup() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getURL()).isEqualTo(POSTGRES.getJdbcUrl());
            assertThat(connection.getCatalog()).isEqualTo("overthinking_outbox_test");
        }
        executor.runAll();
        repository.deleteAll();
        reset(producer);
        clock.now = Instant.parse("2026-09-09T12:00:00Z");
        properties.setMaxAttempts(8);
        transactions = new TransactionTemplate(transactionManager);
        jdbc.execute("CREATE TABLE IF NOT EXISTS outbox_domain_probe (id uuid PRIMARY KEY)");
        jdbc.execute("DELETE FROM outbox_domain_probe");
        // The consumer's permanent receipt migration is a prerequisite, never
        // emulated by an application connection or a reused development database.
        jdbc.execute("CREATE TABLE IF NOT EXISTS tbl_notification_receipt (source_event_id uuid PRIMARY KEY, recipient_id uuid NOT NULL, recorded_at timestamptz NOT NULL DEFAULT now())");
        jdbc.execute(migration());
    }

    @Test void domainCommitPersistsNotificationBeforeDispatchAndBrokerAckClosesIt() {
        NotificationInboundEvent event = OverthinkingOutboxTestEvents.received();
        transactions.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO outbox_domain_probe(id) VALUES (?)", event.eventId());
            publisher.enqueueAll(List.of(event));
            assertThat(executor.tasks).isEmpty();
            verifyNoInteractions(producer);
        });
        assertThat(repository.findById(event.eventId()).orElseThrow().getStatus()).isEqualTo(OverthinkingNotificationOutboxStatus.PENDING);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_domain_probe", Integer.class)).isEqualTo(1);
        assertThat(executor.tasks).hasSize(1);
        executor.runAll();
        verify(producer).publishConfirmed(event);
        verify(producer, never()).publish(any());
        assertThat(repository.findById(event.eventId()).orElseThrow().getStatus()).isEqualTo(OverthinkingNotificationOutboxStatus.PUBLISHED);
    }

    @Test void domainRollbackRemovesOutboxAndDoesNotScheduleDelivery() {
        NotificationInboundEvent event = OverthinkingOutboxTestEvents.received();
        transactions.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO outbox_domain_probe(id) VALUES (?)", event.eventId());
            publisher.enqueueAll(List.of(event));
            status.setRollbackOnly();
        });
        assertThat(repository.count()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_domain_probe", Integer.class)).isZero();
        assertThat(executor.tasks).isEmpty();
        verifyNoInteractions(producer);
    }

    @Test void enqueueRequiresDomainTransaction() {
        assertThatThrownBy(() -> publisher.enqueueAll(List.of(OverthinkingOutboxTestEvents.received())))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(repository.count()).isZero();
        verifyNoInteractions(producer);
    }

    @Test void brokerFailureRemainsDurableAndRetryPreservesWireEventId() {
        NotificationInboundEvent event = OverthinkingOutboxTestEvents.received();
        doThrow(new IllegalStateException("broker password must not be logged")).doNothing()
                .when(producer).publishConfirmed(any());
        transactions.executeWithoutResult(status -> publisher.enqueueAll(List.of(event)));
        executor.runAll();
        var failed = repository.findById(event.eventId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(OverthinkingNotificationOutboxStatus.PENDING);
        assertThat(failed.getAttemptCount()).isEqualTo(1);
        assertThat(failed.getLastErrorType()).isEqualTo(IllegalStateException.class.getName());
        assertThat(service.findDueEventIds(10)).isEmpty();
        clock.now = clock.now.plus(properties.getRetryInitialDelay());
        assertThat(service.findDueEventIds(10)).containsExactly(event.eventId());
        dispatcher.dispatch(event.eventId());
        verify(producer, times(2)).publishConfirmed(event);
        assertThat(repository.findById(event.eventId()).orElseThrow().getStatus()).isEqualTo(OverthinkingNotificationOutboxStatus.PUBLISHED);
    }

    @Test void expiredClaimIsRecoveredAndStaleWorkerCannotAcknowledgeOrReschedule() {
        NotificationInboundEvent event = OverthinkingOutboxTestEvents.received();
        transactions.executeWithoutResult(status -> publisher.enqueueAll(List.of(event)));
        var first = service.claim(event.eventId(), "node:first").orElseThrow();
        executor.runAll();
        assertThat(service.claim(event.eventId(), "node:early")).isEmpty();
        clock.now = clock.now.plus(properties.getLeaseDuration());
        var recovered = service.claim(event.eventId(), "node:recovered").orElseThrow();
        assertThat(recovered.attemptCount()).isEqualTo(2);
        assertThat(service.markPublished(first)).isFalse();
        assertThat(service.markFailed(first, "StaleFailure")).isEqualTo(OverthinkingNotificationOutboxService.FailureDisposition.LEASE_LOST);
        assertThat(service.markPublished(recovered)).isTrue();
        assertThat(service.claim(event.eventId(), "node:late")).isEmpty();
    }

    @Test void repeatedFailureEndsInDeadLetterAndRetentionDoesNotEraseUndeliveredWork() {
        properties.setMaxAttempts(2);
        NotificationInboundEvent event = OverthinkingOutboxTestEvents.received();
        doThrow(new IllegalStateException("offline")).when(producer).publishConfirmed(any());
        transactions.executeWithoutResult(status -> publisher.enqueueAll(List.of(event)));
        executor.runAll();
        clock.now = clock.now.plus(properties.getRetryInitialDelay());
        dispatcher.dispatch(event.eventId());
        assertThat(repository.findById(event.eventId()).orElseThrow().getStatus()).isEqualTo(OverthinkingNotificationOutboxStatus.DEAD_LETTER);
        clock.now = clock.now.plus(properties.getPublishedRetention()).plusSeconds(1);
        assertThat(service.cleanupPublished()).isZero();
        assertThat(service.findDueEventIds(10)).isEmpty();
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test void publishedRetentionWaitsForMatchingConsumerReceipt() {
        var event = OverthinkingOutboxTestEvents.received();
        transactions.executeWithoutResult(status -> publisher.enqueueAll(List.of(event)));
        executor.runAll();
        clock.now = clock.now.plus(properties.getPublishedRetention()).plusSeconds(1);
        assertThat(service.cleanupPublished()).isZero();
        assertThat(repository.existsById(event.eventId())).isTrue();
        jdbc.update("insert into tbl_notification_receipt(source_event_id,recipient_id,recorded_at) values(?,?,current_timestamp)",
                event.eventId(), UUID.randomUUID());
        assertThat(service.cleanupPublished()).isZero();
        jdbc.update("update tbl_notification_receipt set recipient_id=? where source_event_id=?", event.recipientId(), event.eventId());
        assertThat(service.cleanupPublished()).isEqualTo(1);
        assertThat(repository.existsById(event.eventId())).isFalse();
    }

    @Test void migrationIsRerunnableAndRejectsLeakingPayloadAndInvalidState() throws Exception {
        jdbc.execute(migration());
        NotificationInboundEvent event = OverthinkingOutboxTestEvents.received();
        transactions.executeWithoutResult(status -> publisher.enqueueAll(List.of(event)));
        jdbc.execute(migration());
        assertThat(repository.count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM soundconnect_schema_migrations WHERE migration_id='2026-09-09-overthinking-notification-outbox'", Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("UPDATE tbl_overthinking_notification_outbox SET payload=payload || jsonb_build_object('authorId', ?) WHERE event_id=?", UUID.randomUUID().toString(), event.eventId()))
                .hasRootCauseInstanceOf(java.sql.SQLException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE tbl_overthinking_notification_outbox SET payload=jsonb_set(payload,'{module}','null') WHERE event_id=?", event.eventId()))
                .hasRootCauseInstanceOf(java.sql.SQLException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE tbl_overthinking_notification_outbox SET status='IN_FLIGHT' WHERE event_id=?", event.eventId()))
                .hasRootCauseInstanceOf(java.sql.SQLException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE tbl_overthinking_notification_outbox SET attempt_count=-1 WHERE event_id=?", event.eventId()))
                .hasRootCauseInstanceOf(java.sql.SQLException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE tbl_overthinking_notification_outbox SET notification_type='DM_NEW_MESSAGE' WHERE event_id=?", event.eventId()))
                .hasRootCauseInstanceOf(java.sql.SQLException.class);
        assertThat(repository.findById(event.eventId()).orElseThrow().getPayload()).doesNotContainKey("authorId");
    }

    private static String migration() throws Exception {
        return Files.readString(Path.of("scripts/db/2026-09-09-overthinking-notification-outbox.sql"));
    }

    @Configuration(proxyBeanMethods = false)
    @EntityScan(basePackageClasses = {OverthinkingNotificationOutbox.class, NotificationReceipt.class})
    @EnableJpaRepositories(basePackageClasses = OverthinkingNotificationOutboxRepository.class)
    @Import({OverthinkingNotificationOutboxService.class, OverthinkingNotificationOutboxPublisher.class,
            OverthinkingNotificationOutboxDispatcher.class, OverthinkingNotificationDispatchCoordinator.class})
    static class Config {
        @Bean OverthinkingNotificationOutboxProperties properties() { return new OverthinkingNotificationOutboxProperties(); }
        @Bean MutableClock clock() { return new MutableClock(); }
        @Bean NotificationProducer producer() { return mock(NotificationProducer.class); }
        @Bean(name = OverthinkingNotificationOutboxConfiguration.EXECUTOR_BEAN) QueuedExecutor executor() { return new QueuedExecutor(); }
    }

    static final class MutableClock extends OverthinkingNotificationOutboxTimeProvider {
        Instant now = Instant.EPOCH;
        @Override public Instant now() { return now; }
    }

    static final class QueuedExecutor implements Executor {
        final Queue<Runnable> tasks = new ArrayDeque<>();
        @Override public void execute(Runnable command) { tasks.add(command); }
        void runAll() { while (!tasks.isEmpty()) tasks.remove().run(); }
    }
}
