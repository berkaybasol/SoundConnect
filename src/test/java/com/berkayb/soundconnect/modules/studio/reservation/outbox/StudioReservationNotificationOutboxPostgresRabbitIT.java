package com.berkayb.soundconnect.modules.studio.reservation.outbox;

import com.berkayb.soundconnect.modules.notification.config.NotificationRabbitConfig;
import com.berkayb.soundconnect.modules.notification.entity.Notification;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.notification.helper.NotificationBadgeCacheHelper;
import com.berkayb.soundconnect.modules.notification.listener.NotificationEventListener;
import com.berkayb.soundconnect.modules.notification.mapper.NotificationMapper;
import com.berkayb.soundconnect.modules.notification.repository.NotificationReceiptRepository;
import com.berkayb.soundconnect.modules.notification.repository.NotificationRepository;
import com.berkayb.soundconnect.modules.notification.service.NotificationDeliveryPolicy;
import com.berkayb.soundconnect.modules.notification.service.NotificationService;
import com.berkayb.soundconnect.modules.notification.websocket.NotificationWebSocketService;
import com.berkayb.soundconnect.modules.studio.reservation.event.StudioReservationNotificationEvent;
import com.berkayb.soundconnect.modules.studio.reservation.event.StudioReservationNotificationListener;
import com.berkayb.soundconnect.shared.mail.producer.MailProducer;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationInboundEvent;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationProducer;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationPublisherProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static com.berkayb.soundconnect.modules.studio.reservation.outbox.StudioReservationNotificationOutboxStatus.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Production outbox, JPA repositories, publisher and transactional Rabbit listener on disposable services.
 * A JDBC domain marker deliberately replaces the unrelated studio/profile entity graph: the assertions
 * prove the real BEFORE_COMMIT atomic boundary, not the separate booking/overlap business rules.
 * No application.yml, .env, live account, mail sender, websocket or FCM component is loaded.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = StudioReservationNotificationOutboxPostgresRabbitIT.Fixture.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.config.location=optional:classpath:/studio-outbox-isolated-test.yml",
        "spring.config.import=", "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.open-in-view=false",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.rabbitmq.publisher-confirm-type=correlated", "spring.rabbitmq.publisher-returns=true",
        "spring.rabbitmq.template.mandatory=true", "spring.rabbitmq.connection-timeout=2s",
        "spring.rabbitmq.requested-heartbeat=2s",
        "app.messaging.notification.exchange=studio.outbox.test.exchange",
        "app.messaging.notification.queue=studio.outbox.test.queue",
        "app.messaging.notification.routingKey=notification.#",
        "app.messaging.notification.dlxExchange=studio.outbox.test.dlx",
        "app.messaging.notification.dlq=studio.outbox.test.dlq.queue",
        "app.messaging.notification.publisher-confirm-timeout=2s",
        "app.notification.push.enabled=false",
        "logging.level.org.hibernate.SQL=OFF", "logging.level.org.hibernate.orm.jdbc.bind=OFF"
})
@DirtiesContext
class StudioReservationNotificationOutboxPostgresRabbitIT {
    private static final String QUEUE = "studio.outbox.test.queue";
    private static final String DLQ = "studio.outbox.test.dlq.queue";
    private static final Instant START = Instant.parse("2026-09-24T09:00:00Z");
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    @Container static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-alpine");

    @DynamicPropertySource static void services(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({HibernateJpaAutoConfiguration.class, TransactionAutoConfiguration.class,
            JdbcTemplateAutoConfiguration.class, JacksonAutoConfiguration.class, RabbitAutoConfiguration.class})
    @EntityScan(basePackageClasses = {StudioReservationNotificationOutbox.class, Notification.class})
    @EnableJpaRepositories(basePackageClasses = {StudioReservationNotificationOutboxRepository.class, NotificationRepository.class})
    @EnableTransactionManagement(proxyTargetClass = true)
    @Import({StudioReservationNotificationOutboxService.class, StudioReservationNotificationOutboxDispatcher.class,
            StudioReservationNotificationOutboxScheduler.class, StudioReservationNotificationDispatchCoordinator.class,
            StudioReservationNotificationListener.class, NotificationRabbitConfig.class,
            NotificationEventListener.class})
    static class Fixture {
        @Bean DataSource dataSource() throws Exception {
            var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
            var jdbc = new JdbcTemplate(dataSource);
            jdbc.execute("create table tbl_user(id uuid primary key, erased_at timestamptz)");
            jdbc.execute("create table outbox_test_domain_state(id uuid primary key, state varchar(32) not null)");
            jdbc.execute("""
                    create table tbl_notification(
                      id uuid primary key, source_event_id uuid unique, recipient_id uuid not null,
                      type varchar(64) not null, title varchar(160) not null, message varchar(1000) not null,
                      occurred_at timestamptz not null, payload jsonb, is_read boolean not null,
                      created_at timestamp, updated_at timestamp)
                    """);
            jdbc.execute("""
                    create table tbl_notification_receipt(
                      source_event_id uuid primary key, recipient_id uuid not null, recorded_at timestamptz not null)
                    """);
            jdbc.execute(Files.readString(Path.of("scripts/db/2026-09-24-studio-reservation-notification-outbox.sql")));
            return dataSource;
        }
        @Bean StudioReservationNotificationOutboxProperties outboxProperties() {
            return new StudioReservationNotificationOutboxProperties();
        }
        // The suite uses the test profile; explicitly construct the real confirmed publisher
        // without enabling unrelated production-profile components.
        @Bean NotificationProducer notificationProducer(RabbitTemplate rabbit, NotificationPublisherProperties properties) {
            return new NotificationProducer(rabbit, properties);
        }
        @Bean MutableTime time() { return new MutableTime(); }
        @Bean(name = StudioReservationNotificationOutboxConfiguration.EXECUTOR_BEAN)
        ControllableExecutor workers() { return new ControllableExecutor(); }
        @Bean Jackson2JsonMessageConverter messageConverter(ObjectMapper mapper) { return new Jackson2JsonMessageConverter(mapper); }
    }

    private final List<Integer> consumerCompletions = new CopyOnWriteArrayList<>();
    @Autowired StudioReservationNotificationOutboxService outbox;
    @Autowired StudioReservationNotificationOutboxRepository rows;
    @Autowired StudioReservationNotificationOutboxDispatcher dispatcher;
    @Autowired StudioReservationNotificationOutboxScheduler scheduler;
    @Autowired StudioReservationNotificationDispatchCoordinator coordinator;
    @Autowired StudioReservationNotificationOutboxProperties properties;
    @Autowired NotificationProducer producer;
    @Autowired NotificationRepository inbox;
    @Autowired NotificationReceiptRepository receipts;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired ApplicationEventPublisher events;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired RabbitTemplate rabbit;
    @Autowired AmqpAdmin admin;
    @Autowired RabbitListenerEndpointRegistry listeners;
    @Autowired CachingConnectionFactory connections;
    @Autowired MutableTime time;
    @Autowired ControllableExecutor workers;
    @MockitoBean NotificationBadgeCacheHelper badges;
    @MockitoBean NotificationMapper notificationMapper;
    @MockitoBean NotificationWebSocketService websocket;
    @MockitoBean MailProducer mail;
    @MockitoBean NotificationService notificationService;
    @MockitoBean NotificationDeliveryPolicy policy;

    @BeforeEach void resetFixture() {
        listeners.stop();
        workers.reject = false;
        workers.runAll();
        workers.reject = true;
        time.set(START);
        properties.setMaxAttempts(8);
        properties.setBatchSize(25);
        properties.setLeaseDuration(Duration.ofSeconds(30));
        properties.setRetryInitialDelay(Duration.ofSeconds(5));
        properties.setRetryMaxDelay(Duration.ofMinutes(15));
        properties.setPublishedRetention(Duration.ofDays(7));
        ReflectionTestUtils.setField(producer, "publishRoutingKey", "notification.event");
        jdbc.execute("truncate tbl_studio_reservation_notification_outbox, tbl_notification, tbl_notification_receipt, outbox_test_domain_state, tbl_user");
        admin.purgeQueue(QUEUE, false);
        admin.purgeQueue(DLQ, false);
        consumerCompletions.clear();
        when(policy.eligible(any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) { consumerCompletions.add(status); }
            });
            return true;
        });
    }

    @AfterEach void stopConsumers() {
        listeners.stop();
        verifyNoInteractions(mail, websocket, badges);
    }

    @Test void committedDomainChangeAndPendingEventSurviveRejectedImmediateWorker() {
        var event = event(NotificationType.STUDIO_RESERVATION_CREATED, "CREATED", "PENDING_APPROVAL");
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            domainWrite(event.eventId());
            events.publishEvent(event);
            assertThat(rows.findById(event.eventId())).isEmpty();
        });
        assertThat(domainCount()).isEqualTo(1);
        assertThat(row(event).getStatus()).isEqualTo(PENDING);
        assertThat(row(event).getAttemptCount()).isZero();
        assertThat(admin.getQueueInfo(QUEUE).getMessageCount()).isZero();
        assertThat(coordinator.scheduledCount()).isZero();
    }

    @Test void explicitDomainRollbackLeavesNeitherDomainNorOutboxRow() {
        var event = event(NotificationType.STUDIO_RESERVATION_CREATED, "CREATED", "CONFIRMED");
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            domainWrite(event.eventId());
            events.publishEvent(event);
            status.setRollbackOnly();
        });
        assertThat(domainCount()).isZero();
        assertThat(rows.count()).isZero();
        assertThat(coordinator.scheduledCount()).isZero();
    }

    @Test void beforeCommitInsertFailureRollsBackTheDomainWrite() {
        var event = event(NotificationType.STUDIO_RESERVATION_CREATED, "CREATED", "CONFIRMED");
        jdbc.execute("""
                create function outbox_test_fail_insert() returns trigger language plpgsql as $$
                begin raise exception 'fixture unavailable'; end $$;
                create trigger outbox_test_fail before insert on tbl_studio_reservation_notification_outbox
                for each row execute function outbox_test_fail_insert()
                """);
        try {
            assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                domainWrite(event.eventId());
                events.publishEvent(event);
            })).isInstanceOf(RuntimeException.class);
            assertThat(domainCount()).isZero();
            assertThat(rows.count()).isZero();
        } finally {
            jdbc.execute("drop trigger outbox_test_fail on tbl_studio_reservation_notification_outbox");
            jdbc.execute("drop function outbox_test_fail_insert()");
        }
    }

    @Test void eventWithoutDomainTransactionHasNoFallbackDelivery() {
        var event = event(NotificationType.STUDIO_RESERVATION_CREATED, "CREATED", "CONFIRMED");
        events.publishEvent(event);
        assertThat(rows.count()).isZero();
        assertThatThrownBy(() -> outbox.enqueue(event))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    @ParameterizedTest(name = "{0}/{1}/{2}")
    @CsvSource({
        "STUDIO_RESERVATION_CREATED,CREATED,PENDING_APPROVAL",
        "STUDIO_RESERVATION_CREATED,CREATED,CONFIRMED",
        "STUDIO_RESERVATION_CONFLICTING_REQUESTS,CONFLICTING_REQUESTS,PENDING_APPROVAL",
        "STUDIO_RESERVATION_APPROVED,APPROVED,CONFIRMED",
        "STUDIO_RESERVATION_REJECTED,REJECTED,REJECTED_BY_STUDIO",
        "STUDIO_RESERVATION_REJECTED,AUTO_REJECTED_CONFLICT,REJECTED_BY_STUDIO",
        "STUDIO_RESERVATION_CANCELLED_BY_STUDIO,CANCELLED_BY_STUDIO,CANCELLED_BY_STUDIO",
        "STUDIO_RESERVATION_CANCELLED_BY_STUDIO,CANCELLED_BY_STUDIO_ROOM_ARCHIVED,CANCELLED_BY_STUDIO",
        "STUDIO_RESERVATION_CANCELLED_BY_CUSTOMER,CANCELLED_BY_CUSTOMER,CANCELLED_BY_CUSTOMER"
    })
    void everyExistingTypeAndActionPublishesItsOriginalSnapshot(NotificationType type, String action, String status) throws Exception {
        var event = event(type, action, status);
        enqueue(event);
        dispatcher.dispatch(event.eventId());
        assertThat(row(event).getStatus()).isEqualTo(PUBLISHED);
        var message = rabbit.receive(QUEUE, 3000);
        assertThat(message).isNotNull();
        // Spring AMQP moves the broker delivery_mode into receivedDeliveryMode
        // and clears the outbound deliveryMode while converting a received message.
        assertThat(message.getMessageProperties().getReceivedDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(message.getMessageProperties().getCorrelationId()).isEqualTo(event.eventId().toString());
        var inbound = mapper.readValue(message.getBody(), NotificationInboundEvent.class);
        assertThat(inbound.eventId()).isEqualTo(event.eventId());
        assertThat(inbound.recipientId()).isEqualTo(event.recipientId());
        assertThat(inbound.type()).isEqualTo(type);
        assertThat(inbound.title()).isEqualTo(event.title());
        assertThat(inbound.message()).isEqualTo(event.message());
        assertThat(inbound.payload()).isEqualTo(event.payload());
        assertThat(inbound.occurredAt()).isEqualTo(event.occurredAt());
        assertThat(inbound.emailForce()).isFalse();
    }

    @Test void idempotentEnqueueCannotResetAlreadyPublishedStateOrReplaceItsSnapshot() {
        var event = event(NotificationType.STUDIO_RESERVATION_APPROVED, "APPROVED", "CONFIRMED");
        enqueue(event);
        dispatcher.dispatch(event.eventId());
        var replay = new StudioReservationNotificationEvent(event.recipientId(), event.type(), "renamed", "changed",
                event.payload(), START.plusSeconds(20));
        enqueue(replay);
        assertThat(rows.count()).isEqualTo(1);
        assertThat(row(event).getStatus()).isEqualTo(PUBLISHED);
        assertThat(row(event).getAttemptCount()).isEqualTo(1);
        assertThat(row(event).getTitle()).isEqualTo(event.title());
        assertThat(row(event).getOccurredAt()).isEqualTo(event.occurredAt());
    }

    @Test void unroutableConfirmedPublishStaysPendingUntilSchedulerRecoversIt() {
        var event = event(NotificationType.STUDIO_RESERVATION_APPROVED, "APPROVED", "CONFIRMED");
        enqueue(event);
        ReflectionTestUtils.setField(producer, "publishRoutingKey", "unroutable.fixture");
        dispatcher.dispatch(event.eventId());
        assertThat(row(event).getStatus()).isEqualTo(PENDING);
        assertThat(row(event).getLastErrorType()).isEqualTo("org.springframework.amqp.AmqpException");
        assertThat(row(event).getNextAttemptAt()).isEqualTo(START.plusSeconds(5));
        assertThat(admin.getQueueInfo(QUEUE).getMessageCount()).isZero();
        ReflectionTestUtils.setField(producer, "publishRoutingKey", "notification.event");
        time.set(START.plusSeconds(5));
        workers.reject = false;
        scheduler.dispatchDue();
        workers.runAll();
        assertThat(row(event).getStatus()).isEqualTo(PUBLISHED);
        assertThat(row(event).getAttemptCount()).isEqualTo(2);
        assertThat(rabbit.receive(QUEUE, 3000)).isNotNull();
    }

    @Test void stoppedDisposableBrokerDoesNotLoseCommittedPendingWork() throws Exception {
        var event = event(NotificationType.STUDIO_RESERVATION_APPROVED, "APPROVED", "CONFIRMED");
        enqueue(event);
        assertThat(RABBIT.execInContainer("rabbitmqctl", "stop_app").getExitCode()).isZero();
        try {
            connections.resetConnection();
            assertThatCode(() -> dispatcher.dispatch(event.eventId())).doesNotThrowAnyException();
            assertThat(row(event).getStatus()).isEqualTo(PENDING);
            assertThat(row(event).getAttemptCount()).isEqualTo(1);
        } finally {
            assertThat(RABBIT.execInContainer("rabbitmqctl", "start_app").getExitCode()).isZero();
            connections.resetConnection();
            ((org.springframework.amqp.rabbit.core.RabbitAdmin) admin).initialize();
        }
        time.set(START.plusSeconds(5));
        workers.reject = false;
        scheduler.dispatchDue();
        workers.runAll();
        assertThat(row(event).getStatus()).isEqualTo(PUBLISHED);
        assertThat(rabbit.receive(QUEUE, 3000)).isNotNull();
    }

    @Test void leaseRecoveryRejectsStaleOwnerSuccessAndFailureUpdates() {
        var event = event(NotificationType.STUDIO_RESERVATION_APPROVED, "APPROVED", "CONFIRMED");
        enqueue(event);
        var abandoned = outbox.claim(event.eventId(), "abandoned-worker").orElseThrow();
        time.set(START.plusSeconds(31));
        assertThat(outbox.markPublished(abandoned)).isFalse();
        assertThat(outbox.markFailed(abandoned, "java.lang.IllegalStateException"))
                .isEqualTo(StudioReservationNotificationOutboxService.FailureDisposition.LEASE_LOST);
        var recovered = outbox.claim(event.eventId(), "new-worker").orElseThrow();
        assertThat(outbox.markPublished(abandoned)).isFalse();
        assertThat(outbox.markFailed(abandoned, "java.lang.IllegalStateException"))
                .isEqualTo(StudioReservationNotificationOutboxService.FailureDisposition.LEASE_LOST);
        assertThat(row(event).getLeaseOwner()).isEqualTo("new-worker");
        assertThat(outbox.markPublished(recovered)).isTrue();
        assertThat(row(event).getAttemptCount()).isEqualTo(2);
    }

    @Test void brokerConfirmedThenProcessCrashReplaysOnceThroughRealReceiptAndInbox() {
        var event = event(NotificationType.STUDIO_RESERVATION_APPROVED, "APPROVED", "CONFIRMED");
        enqueue(event);
        var abandoned = outbox.claim(event.eventId(), "crashed-after-confirm").orElseThrow();
        listeners.start();
        producer.publishConfirmed(abandoned.toInboundEvent());
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(consumerCompletions).containsExactly(TransactionSynchronization.STATUS_COMMITTED);
            assertThat(inbox.count()).isEqualTo(1);
        });
        // Process dies here, before markPublished: only durable IN_FLIGHT remains.
        assertThat(row(event).getStatus()).isEqualTo(IN_FLIGHT);
        time.set(START.plusSeconds(31));
        workers.reject = false;
        scheduler.dispatchDue();
        workers.runAll();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(consumerCompletions).containsExactly(
                    TransactionSynchronization.STATUS_COMMITTED, TransactionSynchronization.STATUS_COMMITTED);
            verify(policy, times(2)).eligible(any());
            assertThat(receipts.count()).isEqualTo(1);
            assertThat(inbox.count()).isEqualTo(1);
        });
        assertThat(row(event).getStatus()).isEqualTo(PUBLISHED);
        assertThat(row(event).getAttemptCount()).isEqualTo(2);
        verify(policy, times(1)).schedule(any(), any(), any());
        assertThat(rabbit.receive(DLQ, 100)).isNull();
        // A later replay must not resurrect content already removed from the inbox.
        inbox.deleteAll();
        producer.publishConfirmed(abandoned.toInboundEvent());
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(consumerCompletions).containsExactly(
                TransactionSynchronization.STATUS_COMMITTED, TransactionSynchronization.STATUS_COMMITTED,
                TransactionSynchronization.STATUS_COMMITTED));
        verify(policy, times(3)).eligible(any());
        assertThat(inbox.count()).isZero();
        assertThat(receipts.count()).isEqualTo(1);
        verify(policy, times(1)).schedule(any(), any(), any());
    }

    @Test void simultaneousWorkersHaveExactlyOneDatabaseLeaseWinner() throws Exception {
        var event = event(NotificationType.STUDIO_RESERVATION_CREATED, "CREATED", "CONFIRMED");
        enqueue(event);
        int competitors = 12;
        var barrier = new CyclicBarrier(competitors);
        try (var executor = Executors.newFixedThreadPool(competitors)) {
            var tasks = new ArrayList<Future<Optional<StudioReservationNotificationOutboxClaim>>>();
            for (int i = 0; i < competitors; i++) {
                String owner = "worker-" + i;
                tasks.add(executor.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return outbox.claim(event.eventId(), owner);
                }));
            }
            int winners = 0;
            for (var task : tasks) if (task.get(15, TimeUnit.SECONDS).isPresent()) winners++;
            assertThat(winners).isEqualTo(1);
        }
        assertThat(row(event).getAttemptCount()).isEqualTo(1);
        assertThat(row(event).getStatus()).isEqualTo(IN_FLIGHT);
    }

    @Test void retryDelayIsCappedAndAttemptBudgetLeavesInspectableDeadLetter() {
        var event = event(NotificationType.STUDIO_RESERVATION_CREATED, "CREATED", "CONFIRMED");
        properties.setMaxAttempts(3);
        properties.setRetryMaxDelay(Duration.ofSeconds(8));
        enqueue(event);
        var first = outbox.claim(event.eventId(), "attempt-1").orElseThrow();
        assertThat(outbox.markFailed(first, "org.springframework.amqp.AmqpException"))
                .isEqualTo(StudioReservationNotificationOutboxService.FailureDisposition.RETRY_SCHEDULED);
        assertThat(row(event).getNextAttemptAt()).isEqualTo(START.plusSeconds(5));
        assertThat(outbox.claim(event.eventId(), "too-early")).isEmpty();
        time.set(START.plusSeconds(5));
        var second = outbox.claim(event.eventId(), "attempt-2").orElseThrow();
        outbox.markFailed(second, "org.springframework.amqp.AmqpException");
        assertThat(row(event).getNextAttemptAt()).isEqualTo(START.plusSeconds(13));
        time.set(START.plusSeconds(13));
        var third = outbox.claim(event.eventId(), "attempt-3").orElseThrow();
        assertThat(outbox.markFailed(third, "org.springframework.amqp.AmqpException"))
                .isEqualTo(StudioReservationNotificationOutboxService.FailureDisposition.DEAD_LETTER);
        time.set(START.plus(Duration.ofDays(30)));
        assertThat(outbox.findDueEventIds(100)).isEmpty();
        assertThat(outbox.claim(event.eventId(), "attempt-4")).isEmpty();
        assertThat(row(event).getStatus()).isEqualTo(DEAD_LETTER);
        assertThat(row(event).getAttemptCount()).isEqualTo(3);
        assertThat(row(event).getPayload()).isEqualTo(event.payload());
    }

    @Test void repeatedWorkerCrashesAlsoConsumeTheFiniteAttemptBudget() {
        var event = event(NotificationType.STUDIO_RESERVATION_CREATED, "CREATED", "CONFIRMED");
        properties.setMaxAttempts(2);
        enqueue(event);
        assertThat(outbox.claim(event.eventId(), "crash-1")).isPresent();
        time.set(START.plusSeconds(31));
        assertThat(outbox.claim(event.eventId(), "crash-2")).isPresent();
        time.set(START.plusSeconds(62));
        assertThat(outbox.claim(event.eventId(), "crash-3")).isEmpty();
        assertThat(row(event).getStatus()).isEqualTo(DEAD_LETTER);
        assertThat(row(event).getAttemptCount()).isEqualTo(2);
        assertThat(row(event).getLastErrorType()).isEqualTo("AttemptBudgetExhausted");
    }

    @Test void cleanupOnlyDeletesOldPublishedRowsAndPreservesUnresolvedEvidence() {
        var published = event(NotificationType.STUDIO_RESERVATION_CREATED, "CREATED", "CONFIRMED");
        var pending = event(NotificationType.STUDIO_RESERVATION_CREATED, "CREATED", "CONFIRMED");
        var inflight = event(NotificationType.STUDIO_RESERVATION_CREATED, "CREATED", "CONFIRMED");
        var dead = event(NotificationType.STUDIO_RESERVATION_CREATED, "CREATED", "CONFIRMED");
        for (var event : List.of(published, pending, inflight, dead)) enqueue(event);
        outbox.markPublished(outbox.claim(published.eventId(), "published").orElseThrow());
        outbox.claim(inflight.eventId(), "inflight").orElseThrow();
        properties.setMaxAttempts(1);
        outbox.markFailed(outbox.claim(dead.eventId(), "dead").orElseThrow(), "org.springframework.amqp.AmqpException");
        assertThat(outbox.cleanupPublished()).isZero();
        time.set(START.plus(Duration.ofDays(8)));
        assertThat(outbox.cleanupPublished()).isEqualTo(1);
        assertThat(rows.findAll()).extracting(StudioReservationNotificationOutbox::getEventId)
                .containsExactlyInAnyOrder(pending.eventId(), inflight.eventId(), dead.eventId());
    }

    @Test void replayCannotClearAnActiveLeaseOrBypassItsRetryDelay() {
        var event = event(NotificationType.STUDIO_RESERVATION_APPROVED, "APPROVED", "CONFIRMED");
        enqueue(event);
        var claim = outbox.claim(event.eventId(), "original-worker").orElseThrow();
        enqueue(event);
        assertThat(row(event).getStatus()).isEqualTo(IN_FLIGHT);
        assertThat(row(event).getLeaseOwner()).isEqualTo("original-worker");
        assertThat(row(event).getAttemptCount()).isEqualTo(1);
        outbox.markFailed(claim, "org.springframework.amqp.AmqpException");
        enqueue(event);
        assertThat(row(event).getStatus()).isEqualTo(PENDING);
        assertThat(row(event).getAttemptCount()).isEqualTo(1);
        assertThat(row(event).getNextAttemptAt()).isEqualTo(START.plusSeconds(5));
        assertThat(outbox.claim(event.eventId(), "replay-must-wait")).isEmpty();
    }

    @Test void queuedEventsCannotStarveLaterDueEventsAcrossSchedulerBatches() {
        properties.setBatchSize(3);
        var pending = new ArrayList<StudioReservationNotificationEvent>();
        for (int i = 0; i < 12; i++) {
            var event = event(NotificationType.STUDIO_RESERVATION_CREATED, "CREATED", "CONFIRMED");
            pending.add(event);
            enqueue(event);
        }
        workers.reject = false;
        for (int tick = 0; tick < 4; tick++) scheduler.dispatchDue();
        assertThat(coordinator.scheduledCount()).isEqualTo(12);
        // Workers have not begun, so queued work cannot prematurely consume a DB lease.
        assertThat(rows.countByStatus(PENDING)).isEqualTo(12);
        workers.runAll();
        assertThat(coordinator.scheduledCount()).isZero();
        assertThat(rows.countByStatus(PUBLISHED)).isEqualTo(12);
        assertThat(admin.getQueueInfo(QUEUE).getMessageCount()).isEqualTo(12);
    }
    @Test void unavailableRecipientOrRequesterCannotCreateARecoverableDelivery() {
        var erasedRecipient = event(NotificationType.STUDIO_RESERVATION_CREATED, "CREATED", "CONFIRMED");
        jdbc.update("update tbl_user set erased_at=current_timestamp where id=?", erasedRecipient.recipientId());
        enqueue(erasedRecipient);
        var erasedRequester = event(NotificationType.STUDIO_RESERVATION_CREATED, "CREATED", "CONFIRMED");
        jdbc.update("update tbl_user set erased_at=current_timestamp where id=?",
                UUID.fromString((String) erasedRequester.payload().get("requesterId")));
        enqueue(erasedRequester);
        assertThat(rows.count()).isZero();
    }

    private StudioReservationNotificationEvent event(NotificationType type, String action, String status) {
        UUID recipient = UUID.randomUUID(), requester = UUID.randomUUID();
        jdbc.update("insert into tbl_user(id) values (?), (?)", recipient, requester);
        var payload = new LinkedHashMap<String,Object>();
        payload.put("module", "STUDIO"); payload.put("action", action);
        payload.put("reservationId", UUID.randomUUID().toString());
        payload.put("roomId", UUID.randomUUID().toString()); payload.put("roomName", "private-room-fixture");
        payload.put("studioProfileId", UUID.randomUUID().toString()); payload.put("studioName", "private-studio-fixture");
        payload.put("requesterId", requester.toString()); payload.put("zoneId", "Europe/Istanbul");
        payload.put("localDate", "2026-09-25"); payload.put("status", status);
        payload.put("startsAt", "2026-09-25T09:00:00Z"); payload.put("endsAt", "2026-09-25T10:00:00Z");
        return new StudioReservationNotificationEvent(recipient, type, "private-title-fixture", "private-body-fixture", payload, START);
    }
    private void enqueue(StudioReservationNotificationEvent event) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> outbox.enqueue(event));
    }
    private StudioReservationNotificationOutbox row(StudioReservationNotificationEvent event) {
        return rows.findById(event.eventId()).orElseThrow();
    }
    private void domainWrite(UUID id) {
        jdbc.update("insert into outbox_test_domain_state(id,state) values (?, 'CONFIRMED')", id);
    }
    private int domainCount() { return jdbc.queryForObject("select count(*) from outbox_test_domain_state", Integer.class); }

    static class MutableTime extends StudioReservationNotificationOutboxTimeProvider {
        private final AtomicReference<Instant> current = new AtomicReference<>(START);
        @Override public Instant now() { return current.get(); }
        void set(Instant instant) { current.set(instant); }
    }
    static class ControllableExecutor implements Executor {
        volatile boolean reject = true;
        private final ConcurrentLinkedQueue<Runnable> tasks = new ConcurrentLinkedQueue<>();
        @Override public void execute(Runnable task) {
            if (reject) throw new RejectedExecutionException("fixture worker unavailable");
            tasks.add(task);
        }
        void runAll() { for (Runnable task; (task = tasks.poll()) != null;) task.run(); }
    }
}
