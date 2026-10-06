package com.berkayb.soundconnect.modules.notification.outbox;

import com.berkayb.soundconnect.modules.collab.outbox.*;
import com.berkayb.soundconnect.modules.event.performer.outbox.*;
import com.berkayb.soundconnect.modules.overthinking.outbox.*;
import com.berkayb.soundconnect.modules.tablegroup.notification.outbox.*;
import com.berkayb.soundconnect.modules.notification.config.NotificationRabbitConfig;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.notification.helper.NotificationBadgeCacheHelper;
import com.berkayb.soundconnect.modules.notification.listener.NotificationEventListener;
import com.berkayb.soundconnect.modules.notification.mapper.NotificationMapper;
import com.berkayb.soundconnect.modules.notification.service.NotificationDeliveryPolicy;
import com.berkayb.soundconnect.modules.notification.service.NotificationService;
import com.berkayb.soundconnect.modules.notification.websocket.NotificationWebSocketService;
import com.berkayb.soundconnect.shared.mail.producer.MailProducer;
import com.berkayb.soundconnect.shared.messaging.events.notification.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Four independent production JPQL queries and real dispatchers on disposable PG/Rabbit.
 * Reuses the Follow/Studio fixture pattern. External projections and delivery eligibility
 * are mocks here; authenticated domain/eligibility coverage belongs to the HTTP fixture.
 * Abandonment is a committed-claim model, not an actual JVM kill. */
@Testcontainers
@SpringBootTest(classes = NotificationOutboxAttemptBudgetPostgresRabbitIT.Fixture.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.config.location=optional:classpath:/bil004-isolated-test.yml", "spring.config.import=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.open-in-view=false",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.rabbitmq.publisher-confirm-type=correlated", "spring.rabbitmq.publisher-returns=true",
        "spring.rabbitmq.template.mandatory=true", "spring.rabbitmq.connection-timeout=2s",
        "app.messaging.notification.exchange=bil004.exchange", "app.messaging.notification.queue=bil004.queue",
        "app.messaging.notification.routingKey=notification.#", "app.messaging.notification.dlxExchange=bil004.dlx",
        "app.messaging.notification.dlq=bil004.dlq", "app.messaging.notification.publisher-confirm-timeout=2s",
        "logging.level.org.hibernate.SQL=OFF", "logging.level.org.hibernate.orm.jdbc.bind=OFF"
})
@DirtiesContext
class NotificationOutboxAttemptBudgetPostgresRabbitIT {
    static final Instant START = Instant.parse("2026-10-04T09:00:00Z");
    static final String OWNER_LABEL = "bil004-attempt-budget";
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("bil004_attempt_budget").withLabel("soundconnect.fixture", OWNER_LABEL).withReuse(false);
    @Container static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-alpine")
            .withLabel("soundconnect.fixture", OWNER_LABEL).withReuse(false);

    @DynamicPropertySource static void services(DynamicPropertyRegistry r) {
        r.add("spring.rabbitmq.host", RABBIT::getHost);
        r.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        r.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        r.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({HibernateJpaAutoConfiguration.class, TransactionAutoConfiguration.class,
            JdbcTemplateAutoConfiguration.class, JacksonAutoConfiguration.class, RabbitAutoConfiguration.class})
    @EntityScan("com.berkayb.soundconnect")
    @EnableJpaRepositories("com.berkayb.soundconnect")
    @EnableTransactionManagement(proxyTargetClass = true)
    @Import({CollabNotificationOutboxService.class, CollabNotificationOutboxDispatcher.class,
            CollabNotificationOutboxScheduler.class, CollabNotificationDispatchCoordinator.class,
            TableGroupNotificationOutboxService.class, TableGroupNotificationOutboxDispatcher.class,
            TableGroupNotificationOutboxScheduler.class, TableGroupNotificationDispatchCoordinator.class,
            EventPerformerNotificationOutboxService.class, EventPerformerNotificationOutboxDispatcher.class,
            EventPerformerNotificationOutboxScheduler.class, EventPerformerNotificationDispatchCoordinator.class,
            OverthinkingNotificationOutboxService.class, OverthinkingNotificationOutboxDispatcher.class,
            OverthinkingNotificationOutboxScheduler.class, OverthinkingNotificationDispatchCoordinator.class,
            NotificationRabbitConfig.class, NotificationEventListener.class,
            com.berkayb.soundconnect.shared.config.JpaAuditingConfig.class})
    static class Fixture {
        @Bean DataSource dataSource() throws Exception {
            assertThat(POSTGRES.getContainerInfo().getConfig().getLabels()).containsEntry("soundconnect.fixture", OWNER_LABEL);
            var ds = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
            // Verify ownership before Hibernate performs even its first DDL operation.
            verifyDatabase(ds);
            return ds;
        }
        @Bean CollabNotificationOutboxProperties collabProperties() { return new CollabNotificationOutboxProperties(); }
        @Bean TableGroupNotificationOutboxProperties tableProperties() { return new TableGroupNotificationOutboxProperties(); }
        @Bean EventPerformerNotificationOutboxProperties eventProperties() { return new EventPerformerNotificationOutboxProperties(); }
        @Bean OverthinkingNotificationOutboxProperties overthinkingProperties() { return new OverthinkingNotificationOutboxProperties(); }
        @Bean NotificationProducer producer(RabbitTemplate rabbit, NotificationPublisherProperties p) { return new NotificationProducer(rabbit, p); }
        @Bean Jackson2JsonMessageConverter converter(ObjectMapper mapper) { return new Jackson2JsonMessageConverter(mapper); }
        @Bean(name = {CollabNotificationOutboxConfiguration.EXECUTOR_BEAN,
                TableGroupNotificationOutboxConfiguration.EXECUTOR_BEAN,
                EventPerformerNotificationOutboxConfiguration.EXECUTOR_BEAN,
                OverthinkingNotificationOutboxConfiguration.EXECUTOR_BEAN})
        Workers workers() { return new Workers(); }
    }

    @Autowired ApplicationContext context;
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired Workers workers;
    @Autowired AmqpAdmin admin;
    @Autowired RabbitListenerEndpointRegistry listeners;
    @MockitoSpyBean NotificationProducer producer;
    @MockitoBean CollabNotificationOutboxTimeProvider collabTime;
    @MockitoBean TableGroupNotificationOutboxTimeProvider tableTime;
    @MockitoBean EventPerformerNotificationOutboxTimeProvider eventTime;
    @MockitoBean OverthinkingNotificationOutboxTimeProvider overthinkingTime;
    @MockitoBean NotificationBadgeCacheHelper badges;
    @MockitoBean NotificationMapper mapper;
    @MockitoBean NotificationWebSocketService websocket;
    @MockitoBean MailProducer mail;
    @MockitoBean NotificationService notifications;
    @MockitoBean NotificationDeliveryPolicy policy;
    volatile Instant now;
    final List<Integer> consumerCommits = new CopyOnWriteArrayList<>();

    static void verifyDatabase(DataSource ds) throws Exception {
        try (var c = ds.getConnection()) {
            assertThat(c.getMetaData().getURL()).isEqualTo(POSTGRES.getJdbcUrl());
            assertThat(c.getCatalog()).isEqualTo("bil004_attempt_budget");
        }
    }

    @BeforeEach void resetFixture() throws Exception {
        verifyDatabase(dataSource);
        assertThat(RABBIT.getContainerInfo().getConfig().getLabels()).containsEntry("soundconnect.fixture", OWNER_LABEL);
        listeners.stop();
        workers.runAll();
        workers.reject = false;
        now = START;
        when(collabTime.now()).thenAnswer(i -> now);
        when(tableTime.now()).thenAnswer(i -> now);
        when(eventTime.now()).thenAnswer(i -> now);
        when(overthinkingTime.now()).thenAnswer(i -> now);
        for (Family f : Family.values()) {
            jdbc.execute("truncate " + f.table);
            configure(f, 2, 1);
        }
        jdbc.execute("truncate tbl_notification, tbl_notification_receipt");
        admin.purgeQueue("bil004.queue", false);
        admin.purgeQueue("bil004.dlq", false);
        ReflectionTestUtils.setField(producer, "publishRoutingKey", "notification.event");
        consumerCommits.clear();
        when(policy.eligible(any())).thenAnswer(i -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) { consumerCommits.add(status); }
            });
            return true;
        });
    }

    @ParameterizedTest(name = "{displayName} [{index}] {0}") @EnumSource(Family.class)
    void repeatedAbandonedCommittedClaimsExhaustBudgetWithoutAnotherPublish(Family f) {
        var h = harness(f); UUID id = seed(f);
        var snapshot = immutable(f, id);
        assertThat(h.claim.apply(id, "first").orElseThrow().attempt).isEqualTo(1);
        now = START.plusSeconds(30);
        assertThat(h.claim.apply(id, "last").orElseThrow().attempt).isEqualTo(2);
        now = START.plusSeconds(60);
        assertThat(h.claim.apply(id, "exhausted")).isEmpty();
        assertTerminal(f, id, 2);
        h.dispatch.accept(id);
        verifyNoInteractions(producer);
        assertThat(immutable(f, id)).isEqualTo(snapshot);
        evidence(f, id, "abandoned-claims", 0);
    }

    @ParameterizedTest(name = "{displayName} [{index}] {0}") @EnumSource(Family.class)
    void eligibilityBoundariesAndLegacyOverBudgetRowsKeepTheirSnapshot(Family f) {
        var h = harness(f);
        for (int attempts : List.of(2, 3)) {
            for (String state : List.of("PENDING", "IN_FLIGHT", "PUBLISHED", "DEAD_LETTER")) {
                for (int offset : state.equals("IN_FLIGHT") ? List.of(-1, 0, 1, 99) : List.of(0, 1)) {
                    UUID id = seed(f);
                    Timestamp lease = offset == 99 ? null : Timestamp.from(START.plusSeconds(offset));
                    jdbc.update("update " + f.table + " set status=?,attempt_count=?,next_attempt_at=?,lease_owner='original',lease_until=? where event_id=?",
                            state, attempts, Timestamp.from(START.plusSeconds(state.equals("PENDING") ? offset : 0)), lease, id);
                    var snapshot = immutable(f, id);
                    assertThat(h.claim.apply(id, "unexpected")).isEmpty();
                    boolean exhausted = (state.equals("PENDING") && offset == 0)
                            || (state.equals("IN_FLIGHT") && offset != 1);
                    if (exhausted) assertTerminal(f, id, attempts);
                    else {
                        assertThat(row(f, id)).containsEntry("status", state).containsEntry("attempt_count", attempts)
                                .containsEntry("lease_owner", "original").containsEntry("lease_until", lease);
                    }
                    assertThat(immutable(f, id)).isEqualTo(snapshot);
                }
            }
        }
    }

    @ParameterizedTest(name = "{displayName} [{index}] {0}") @EnumSource(Family.class)
    void maxOneAllowsFinalClaimAndProtectsActiveLeaseAndSuccessfulFinalization(Family f) {
        configure(f, 1, 1); var h = harness(f); UUID id = seed(f);
        var claim = h.claim.apply(id, "last").orElseThrow();
        assertThat(claim.attempt).isEqualTo(1);
        now = START.plusSeconds(29);
        assertThat(h.claim.apply(id, "too-early")).isEmpty();
        assertThat(row(f, id)).containsEntry("status", "IN_FLIGHT").containsEntry("lease_owner", "last");
        assertThat(claim.published.getAsBoolean()).isTrue();
        now = START.plusSeconds(30); h.dispatch.accept(id);
        assertThat(row(f, id)).containsEntry("status", "PUBLISHED").containsEntry("attempt_count", 1);
        verifyNoInteractions(producer);
    }

    @ParameterizedTest(name = "{displayName} [{index}] {0}") @EnumSource(Family.class)
    void twoIndependentDatabaseWorkersRaceForLastAttemptAndStaleCallbacksCannotReviveTerminal(Family f) throws Exception {
        var h = harness(f); UUID id = seed(f);
        var old = h.claim.apply(id, "old").orElseThrow();
        now = START.plusSeconds(30);
        try (var lock = dataSource.getConnection(); var pool = Executors.newFixedThreadPool(2)) {
            lock.setAutoCommit(false);
            try (var s = lock.prepareStatement("select event_id from " + f.table + " where event_id=? for update")) {
                s.setObject(1, id); s.executeQuery().close();
            }
            var gate = new CountDownLatch(1);
            var a = pool.submit(() -> { gate.await(); return h.claim.apply(id, "worker-a"); });
            var b = pool.submit(() -> { gate.await(); return h.claim.apply(id, "worker-b"); });
            try {
                gate.countDown();
                await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(jdbc.queryForList(
                        "select distinct pid from pg_stat_activity where datname=current_database() and wait_event_type='Lock' and query like ?",
                        Integer.class, "update " + f.table + "%")).hasSize(2));
            } finally { lock.commit(); }
            var claims = List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
            assertThat(claims.stream().filter(Optional::isPresent).count()).isEqualTo(1);
            var winner = claims.stream().flatMap(Optional::stream).findFirst().orElseThrow();
            assertThat(winner.attempt).isEqualTo(2);
            assertThat(old.published.getAsBoolean()).isFalse();
            assertThat(old.failed.apply("OldFailure")).isEqualTo("LEASE_LOST");
            assertThat(row(f, id)).containsEntry("status", "IN_FLIGHT").containsEntry("attempt_count", 2);
            now = START.plusSeconds(60);
            assertThat(h.claim.apply(id, "terminal")).isEmpty();
            assertThat(winner.published.getAsBoolean()).isFalse();
            assertThat(winner.failed.apply("StaleFailure")).isEqualTo("LEASE_LOST");
            assertTerminal(f, id, 2);
        }
    }

    @ParameterizedTest(name = "{displayName} [{index}] {0}") @EnumSource(Family.class)
    void expiredFinalCallbackAndTerminalRecoverySerializeWithoutResurrection(Family f) throws Exception {
        configure(f, 1, 1); var h = harness(f);
        for (boolean publishSuccess : List.of(true, false)) {
            now = START; UUID id = seed(f);
            var last = h.claim.apply(id, "last").orElseThrow();
            now = START.plusSeconds(30);
            try (var lock = dataSource.getConnection(); var pool = Executors.newFixedThreadPool(2)) {
                lock.setAutoCommit(false);
                try (var s = lock.prepareStatement("select event_id from " + f.table + " where event_id=? for update")) {
                    s.setObject(1, id); s.executeQuery().close();
                }
                var gate = new CountDownLatch(1);
                var recovery = pool.submit(() -> { gate.await(); return h.claim.apply(id, "recovery"); });
                var callback = pool.submit(() -> {
                    gate.await();
                    return publishSuccess ? last.published.getAsBoolean() : last.failed.apply("FinalFailure").equals("DEAD_LETTER");
                });
                try {
                    gate.countDown();
                    await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(jdbc.queryForList(
                            "select distinct pid from pg_stat_activity where datname=current_database() and wait_event_type='Lock' and query like ?",
                            Integer.class, "update " + f.table + "%")).hasSize(2));
                } finally { lock.commit(); }
                assertThat(recovery.get(10, TimeUnit.SECONDS)).isEmpty();
                boolean finalized = callback.get(10, TimeUnit.SECONDS);
                assertThat(row(f, id)).containsEntry("attempt_count", 1).containsEntry("lease_owner", null).containsEntry("lease_until", null)
                        .containsEntry("status", publishSuccess && finalized ? "PUBLISHED" : "DEAD_LETTER");
                assertThat(last.published.getAsBoolean()).isFalse();
                assertThat(last.failed.apply("StaleFailure")).isEqualTo("LEASE_LOST");
                h.dispatch.accept(id);
                verifyNoInteractions(producer);
            }
        }
    }

    @ParameterizedTest(name = "{displayName} [{index}] {0}") @EnumSource(Family.class)
    void actualBrokerAckThenFinalDatabaseRollbackConsumesBudgetAndDeduplicatesReadAndDeletedInbox(Family f) throws Exception {
        var h = harness(f); UUID id = seed(f); var snapshot = immutable(f, id);
        listeners.start();
        installFailure(f, "NEW.status='PUBLISHED'");
        try {
            assertThatThrownBy(() -> h.dispatch.accept(id)).isInstanceOf(RuntimeException.class);
            awaitConsumer(1);
            assertThat(row(f, id)).containsEntry("status", "IN_FLIGHT").containsEntry("attempt_count", 1);
            jdbc.update("update tbl_notification set is_read=true where source_event_id=?", id);
            now = START.plusSeconds(30);
            assertThatThrownBy(() -> h.dispatch.accept(id)).isInstanceOf(RuntimeException.class);
            awaitConsumer(2);
            assertThat(jdbc.queryForObject("select is_read from tbl_notification where source_event_id=?", Boolean.class, id)).isTrue();
            now = START.plusSeconds(60);
            h.dispatch.accept(id);
            assertTerminal(f, id, 2);
            verify(producer, times(2)).publishConfirmed(any());
            assertThat(immutable(f, id)).isEqualTo(snapshot);
            assertThat(jdbc.queryForObject("select count(*) from tbl_notification_receipt where source_event_id=?", Integer.class, id)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from tbl_notification where source_event_id=?", Integer.class, id)).isEqualTo(1);
            // A transport redelivery after inbox deletion remains deduplicated even in terminal state.
            var captured = org.mockito.ArgumentCaptor.forClass(NotificationInboundEvent.class);
            verify(producer, times(2)).publishConfirmed(captured.capture());
            assertThat(captured.getAllValues().get(0)).isEqualTo(captured.getAllValues().get(1));
            jdbc.update("delete from tbl_notification where source_event_id=?", id);
            producer.publishConfirmed(captured.getValue());
            awaitConsumer(3);
            assertThat(jdbc.queryForObject("select count(*) from tbl_notification where source_event_id=?", Integer.class, id)).isZero();
            evidence(f, id, "actual-confirm-final-db-rollback", 2);
        } finally { removeFailure(f); }
    }

    @ParameterizedTest(name = "{displayName} [{index}] {0}") @EnumSource(Family.class)
    void failedBookkeepingRollbackCannotOpenAnotherAttempt(Family f) throws Exception {
        var h = harness(f); UUID id = seed(f);
        ReflectionTestUtils.setField(producer, "publishRoutingKey", "unroutable.fixture");
        installFailure(f, "OLD.status='IN_FLIGHT' and NEW.status in ('PENDING','DEAD_LETTER')");
        try {
            for (int i = 0; i < 2; i++) {
                now = START.plusSeconds(30L * i);
                assertThatThrownBy(() -> h.dispatch.accept(id)).isInstanceOf(RuntimeException.class);
                assertThat(row(f, id)).containsEntry("status", "IN_FLIGHT").containsEntry("attempt_count", i + 1);
            }
        } finally { removeFailure(f); }
        now = START.plusSeconds(60); h.dispatch.accept(id);
        assertTerminal(f, id, 2);
        verify(producer, times(2)).publishConfirmed(any());
    }

    @ParameterizedTest(name = "{displayName} [{index}] {0}") @EnumSource(Family.class)
    void normalFailureBackoffAndRetryOrDeadLetterPreserveStableEvent(Family f) {
        var h = harness(f); UUID success = seed(f), exhausted = seed(f);
        ReflectionTestUtils.setField(producer, "publishRoutingKey", "unroutable.fixture");
        h.dispatch.accept(success); h.dispatch.accept(exhausted);
        assertThat(row(f, success)).containsEntry("status", "PENDING").containsEntry("attempt_count", 1)
                .containsEntry("next_attempt_at", Timestamp.from(START.plusSeconds(5)));
        h.dispatch.accept(success); verify(producer, times(2)).publishConfirmed(any());
        now = START.plusSeconds(5); h.dispatch.accept(exhausted);
        assertThat(row(f, exhausted)).containsEntry("status", "DEAD_LETTER").containsEntry("attempt_count", 2);
        ReflectionTestUtils.setField(producer, "publishRoutingKey", "notification.event");
        listeners.start(); h.dispatch.accept(success); awaitConsumer(1);
        assertThat(row(f, success)).containsEntry("status", "PUBLISHED").containsEntry("attempt_count", 2);
        var captor = org.mockito.ArgumentCaptor.forClass(NotificationInboundEvent.class);
        verify(producer, times(4)).publishConfirmed(captor.capture());
        assertThat(captor.getAllValues().get(0)).isEqualTo(captor.getAllValues().get(3));
    }

    @ParameterizedTest(name = "{displayName} [{index}] {0}") @EnumSource(Family.class)
    void schedulerRejectionAndExhaustedFirstBatchDoNotStarveHealthyWorkAndCleanupKeepsEvidence(Family f) {
        var h = harness(f); UUID dead = seed(f);
        jdbc.update("update " + f.table + " set attempt_count=2,next_attempt_at=? where event_id=?", Timestamp.from(START.minusSeconds(1)), dead);
        UUID healthy = seed(f);
        workers.reject = true; h.schedule.run();
        assertThat(row(f, dead)).containsEntry("status", "PENDING");
        workers.reject = false; h.schedule.run(); h.schedule.run();
        assertThat(workers.tasks).hasSize(2); // queued exhausted and next healthy, each only once
        listeners.start(); workers.runAll(); awaitConsumer(1);
        assertTerminal(f, dead, 2);
        assertThat(row(f, healthy)).containsEntry("status", "PUBLISHED");
        UUID unresolved = seed(f), missingReceipt = seed(f);
        jdbc.update("update " + f.table + " set status='PUBLISHED',published_at=? where event_id=?", Timestamp.from(START), missingReceipt);
        now = START.plus(Duration.ofDays(8)); h.cleanup.getAsInt();
        assertThat(jdbc.queryForObject("select count(*) from " + f.table + " where event_id=?", Integer.class, healthy)).isZero();
        assertThat(row(f, dead)).containsEntry("status", "DEAD_LETTER").containsEntry("attempt_count", 2);
        assertThat(row(f, unresolved)).containsEntry("status", "PENDING");
        assertThat(jdbc.queryForObject("select count(*) from " + f.table + " where event_id=?", Integer.class, missingReceipt))
                .isEqualTo(f == Family.OVERTHINKING ? 1 : 0);
    }

    enum Family {
        COLLAB("tbl_collab_notification_outbox", NotificationType.COLLAB_APPLICATION_RECEIVED),
        TABLE("tbl_table_group_notification_outbox", NotificationType.TABLE_JOIN_REQUEST_RECEIVED),
        EVENT("tbl_event_performer_notification_outbox", NotificationType.EVENT_PERFORMER_APPROVAL_REQUESTED),
        OVERTHINKING("tbl_overthinking_notification_outbox", NotificationType.OVERTHINKING_REVEAL_REQUEST_RECEIVED);
        final String table; final NotificationType type;
        Family(String table, NotificationType type) { this.table = table; this.type = type; }
    }
    record Lease(int attempt, BooleanSupplier published, Function<String, String> failed) { }
    record Harness(BiFunction<UUID, String, Optional<Lease>> claim, Consumer<UUID> dispatch,
                   Runnable schedule, IntSupplier cleanup) { }

    Harness harness(Family f) {
        return switch (f) {
            case COLLAB -> {
                var s = context.getBean(CollabNotificationOutboxService.class);
                yield new Harness((id, owner) -> s.claim(id, owner).map(c -> new Lease(c.attemptCount(), () -> s.markPublished(c), e -> s.markFailed(c, e).name())),
                        context.getBean(CollabNotificationOutboxDispatcher.class)::dispatch, context.getBean(CollabNotificationOutboxScheduler.class)::dispatchDue, s::cleanupPublished);
            }
            case TABLE -> {
                var s = context.getBean(TableGroupNotificationOutboxService.class);
                yield new Harness((id, owner) -> s.claim(id, owner).map(c -> new Lease(c.attemptCount(), () -> s.markPublished(c), e -> s.markFailed(c, e).name())),
                        context.getBean(TableGroupNotificationOutboxDispatcher.class)::dispatch, context.getBean(TableGroupNotificationOutboxScheduler.class)::dispatchDue, s::cleanupPublished);
            }
            case EVENT -> {
                var s = context.getBean(EventPerformerNotificationOutboxService.class);
                yield new Harness((id, owner) -> s.claim(id, owner).map(c -> new Lease(c.attemptCount(), () -> s.markPublished(c), e -> s.markFailed(c, e).name())),
                        context.getBean(EventPerformerNotificationOutboxDispatcher.class)::dispatch, context.getBean(EventPerformerNotificationOutboxScheduler.class)::dispatchDue, s::cleanupPublished);
            }
            case OVERTHINKING -> {
                var s = context.getBean(OverthinkingNotificationOutboxService.class);
                yield new Harness((id, owner) -> s.claim(id, owner).map(c -> new Lease(c.attemptCount(), () -> s.markPublished(c), e -> s.markFailed(c, e).name())),
                        context.getBean(OverthinkingNotificationOutboxDispatcher.class)::dispatch, context.getBean(OverthinkingNotificationOutboxScheduler.class)::dispatchDue, s::cleanupPublished);
            }
        };
    }

    void configure(Family f, int max, int batch) {
        // Property types are deliberately independent in production.
        switch (f) {
            case COLLAB -> { var p = context.getBean(CollabNotificationOutboxProperties.class); p.setMaxAttempts(max); p.setBatchSize(batch); p.setLeaseDuration(Duration.ofSeconds(30)); p.setRetryInitialDelay(Duration.ofSeconds(5)); }
            case TABLE -> { var p = context.getBean(TableGroupNotificationOutboxProperties.class); p.setMaxAttempts(max); p.setBatchSize(batch); p.setLeaseDuration(Duration.ofSeconds(30)); p.setRetryInitialDelay(Duration.ofSeconds(5)); }
            case EVENT -> { var p = context.getBean(EventPerformerNotificationOutboxProperties.class); p.setMaxAttempts(max); p.setBatchSize(batch); p.setLeaseDuration(Duration.ofSeconds(30)); p.setRetryInitialDelay(Duration.ofSeconds(5)); }
            case OVERTHINKING -> { var p = context.getBean(OverthinkingNotificationOutboxProperties.class); p.setMaxAttempts(max); p.setBatchSize(batch); p.setLeaseDuration(Duration.ofSeconds(30)); p.setRetryInitialDelay(Duration.ofSeconds(5)); }
        }
    }
    UUID seed(Family f) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into " + f.table + " (event_id,recipient_id,notification_type,title,message,payload,email_force,occurred_at,status,attempt_count,next_attempt_at,created_at,updated_at) values (?,?,?,'Fixture','Snapshot','{\"fixture\":\"bil004\"}'::jsonb,false,?,'PENDING',0,?,?,?)",
                id, UUID.randomUUID(), f.type.name(), Timestamp.from(START), Timestamp.from(START), Timestamp.from(START), Timestamp.from(START));
        return id;
    }
    Map<String, Object> row(Family f, UUID id) { return jdbc.queryForMap("select * from " + f.table + " where event_id=?", id); }
    Map<String, Object> immutable(Family f, UUID id) {
        return jdbc.queryForMap("select event_id,recipient_id,notification_type,title,message,payload::text,email_force,occurred_at,next_attempt_at,created_at from " + f.table + " where event_id=?", id);
    }
    void assertTerminal(Family f, UUID id, int attempts) {
        assertThat(row(f, id)).containsEntry("status", "DEAD_LETTER").containsEntry("attempt_count", attempts)
                .containsEntry("lease_owner", null).containsEntry("lease_until", null)
                .containsEntry("last_error_type", "AttemptBudgetExhausted").containsEntry("updated_at", Timestamp.from(now));
    }
    void awaitConsumer(int count) {
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(consumerCommits).hasSize(count).containsOnly(TransactionSynchronization.STATUS_COMMITTED));
    }
    void installFailure(Family f, String condition) throws Exception {
        verifyDatabase(dataSource);
        jdbc.execute("create function bil004_fail_mark() returns trigger language plpgsql as $$ begin if " + condition + " then raise exception 'bil004 fixture rollback'; end if; return NEW; end $$");
        jdbc.execute("create trigger bil004_fail_mark before update on " + f.table + " for each row execute function bil004_fail_mark()");
    }
    void removeFailure(Family f) throws Exception {
        verifyDatabase(dataSource);
        jdbc.execute("drop trigger if exists bil004_fail_mark on " + f.table);
        jdbc.execute("drop function if exists bil004_fail_mark()");
    }
    void evidence(Family f, UUID id, String scenario, int dispatchPublishes) {
        System.out.println("BIL004_EVIDENCE family=" + f + " scenario=" + scenario + " eventId=" + id
                + " recipient=" + row(f, id).get("recipient_id") + " state=" + row(f, id).get("status")
                + " attempts=" + row(f, id).get("attempt_count") + " dispatchPublishes=" + dispatchPublishes
                + " db=" + POSTGRES.getDatabaseName() + " pgContainer=" + POSTGRES.getContainerId()
                + " rabbitContainer=" + RABBIT.getContainerId());
    }
    static class Workers implements Executor {
        boolean reject;
        final Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();
        @Override public void execute(Runnable command) {
            if (reject) throw new RejectedExecutionException("fixture full");
            tasks.add(command);
        }
        void runAll() { for (Runnable task; (task = tasks.poll()) != null;) task.run(); }
    }
}
