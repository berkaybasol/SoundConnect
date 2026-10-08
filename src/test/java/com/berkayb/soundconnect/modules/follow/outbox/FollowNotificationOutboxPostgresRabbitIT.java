package com.berkayb.soundconnect.modules.follow.outbox;

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
import com.berkayb.soundconnect.modules.follow.service.FollowServiceImpl;
import com.berkayb.soundconnect.modules.follow.band.service.BandFollowServiceImpl;
import com.berkayb.soundconnect.modules.follow.entity.Follow;
import com.berkayb.soundconnect.modules.follow.band.entity.BandFollow;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.*;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandMemberShipStatus;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.entity.ListenerProfile;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.modules.notification.push.*;
import com.berkayb.soundconnect.modules.profile.shared.identity.GhostListenerIdentityBatchResolver;
import com.berkayb.soundconnect.modules.profile.shared.resolver.service.PublicProfileResolverService;
import com.berkayb.soundconnect.modules.profile.shared.resolver.dto.UserProfilesResolveResponseDto;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import jakarta.persistence.EntityManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static com.berkayb.soundconnect.modules.follow.outbox.FollowNotificationOutboxStatus.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real follow/band services, domain repositories, current ghost resolver, durable outbox,
 * confirmed publisher, Rabbit listener and receipt/inbox on disposable PG/Rabbit.
 * Public profile enrichment/media and external delivery projections are mocks; no app config is imported. */
@Testcontainers
@SpringBootTest(classes = FollowNotificationOutboxPostgresRabbitIT.Fixture.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.config.location=optional:classpath:/studio-outbox-isolated-test.yml",
        "spring.config.import=", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.open-in-view=false",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.rabbitmq.publisher-confirm-type=correlated", "spring.rabbitmq.publisher-returns=true",
        "spring.rabbitmq.template.mandatory=true", "spring.rabbitmq.connection-timeout=2s",
        "spring.rabbitmq.requested-heartbeat=2s",
        "app.messaging.notification.exchange=follow.outbox.test.exchange",
        "app.messaging.notification.queue=follow.outbox.test.queue",
        "app.messaging.notification.routingKey=notification.#",
        "app.messaging.notification.dlxExchange=follow.outbox.test.dlx",
        "app.messaging.notification.dlq=follow.outbox.test.dlq.queue",
        "app.messaging.notification.publisher-confirm-timeout=2s",
        "app.notification.push.enabled=false",
        "logging.level.org.hibernate.SQL=OFF", "logging.level.org.hibernate.orm.jdbc.bind=OFF"
})
@DirtiesContext
class FollowNotificationOutboxPostgresRabbitIT {
    private static final String QUEUE = "follow.outbox.test.queue";
    private static final String DLQ = "follow.outbox.test.dlq.queue";
    private static final Instant START = Instant.parse("2026-09-27T09:00:00Z");
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
    @EntityScan(basePackages = "com.berkayb.soundconnect")
    @EnableJpaRepositories(basePackages = "com.berkayb.soundconnect")
    @EnableTransactionManagement(proxyTargetClass = true)
    @Import({FollowNotificationOutboxService.class, FollowNotificationOutboxDispatcher.class,
            FollowNotificationOutboxScheduler.class, FollowNotificationDispatchCoordinator.class,
            FollowNotificationPublisher.class, FollowServiceImpl.class, BandFollowServiceImpl.class,
            com.berkayb.soundconnect.modules.user.support.UserEntityFinder.class,
            com.berkayb.soundconnect.modules.profile.MusicianProfile.band.support.BandEntityFinder.class,
            com.berkayb.soundconnect.modules.profile.ListenerProfile.support.ListenerVisibilityPolicy.class,
            com.berkayb.soundconnect.modules.profile.ListenerProfile.support.ListenerProfileChoiceStatusReader.class,
            com.berkayb.soundconnect.modules.user.support.AccountDeliveryFence.class,
            GhostListenerIdentityBatchResolver.class,
            com.berkayb.soundconnect.shared.config.JpaAuditingConfig.class, NotificationRabbitConfig.class,
            NotificationEventListener.class})
    static class Fixture {
        @Bean DataSource dataSource() throws Exception {
            var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
            return new CountingDataSource(dataSource);
        }
        @Bean FollowNotificationOutboxProperties outboxProperties() {
            return new FollowNotificationOutboxProperties();
        }
        // The suite uses the test profile; explicitly construct the real confirmed publisher
        // without enabling unrelated production-profile components.
        @Bean NotificationProducer notificationProducer(RabbitTemplate rabbit, NotificationPublisherProperties properties) {
            return new NotificationProducer(rabbit, properties);
        }
        @Bean com.berkayb.soundconnect.modules.follow.band.mapper.BandFollowMapper bandMapper() {
            return org.mapstruct.factory.Mappers.getMapper(com.berkayb.soundconnect.modules.follow.band.mapper.BandFollowMapper.class);
        }
        @Bean PushProperties pushProperties() { return new PushProperties(); }
        @Bean PushDeliveryPlanner pushPlanner(org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc, PushProperties p) {
            return new PushDeliveryPlanner(jdbc,p,java.time.Clock.fixed(START,java.time.ZoneOffset.UTC));
        }
        @Bean MutableTime time() { return new MutableTime(); }
        @Bean(name = FollowNotificationOutboxConfiguration.EXECUTOR_BEAN)
        ControllableExecutor workers() { return new ControllableExecutor(); }
        @Bean Jackson2JsonMessageConverter messageConverter(ObjectMapper mapper) { return new Jackson2JsonMessageConverter(mapper); }
    }

    private final List<Integer> consumerCompletions = new CopyOnWriteArrayList<>();
    @Autowired FollowNotificationOutboxService outbox;
    @Autowired FollowNotificationOutboxRepository rows;
    @Autowired FollowNotificationOutboxDispatcher dispatcher;
    @Autowired FollowNotificationOutboxScheduler scheduler;
    @Autowired FollowNotificationDispatchCoordinator coordinator;
    @Autowired FollowNotificationOutboxProperties properties;
    @MockitoSpyBean NotificationProducer producer;
    @Autowired FollowNotificationPublisher publisher;
    @Autowired FollowServiceImpl follows;
    @Autowired BandFollowServiceImpl bandFollows;
    @Autowired EntityManager em;
    @Autowired PushProperties pushProperties;
    @MockitoSpyBean GhostListenerIdentityBatchResolver identities;
    @MockitoBean PublicProfileResolverService publicProfiles;
    @MockitoBean com.berkayb.soundconnect.modules.media.service.MediaAssetService media;

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

    @BeforeEach void resetFixture() throws Exception {
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
        jdbc.execute(Files.readString(Path.of("scripts/db/2026-09-27-follow-notification-outbox.sql")));
        jdbc.execute("truncate tbl_follow_notification_outbox, tbl_notification, tbl_notification_receipt, tbl_user cascade");
        jdbc.execute("create table if not exists tbl_push_delivery(id uuid primary key)");
        when(publicProfiles.resolveByUserId(any())).thenAnswer(inv -> new UserProfilesResolveResponseDto(inv.getArgument(0),List.of()));
        pushProperties.setAllowedTypes(java.util.EnumSet.of(NotificationType.DM_NEW_MESSAGE,
            NotificationType.ARTIST_VENUE_LINK_APPLICATION_REQUEST,NotificationType.ARTIST_VENUE_LINK_APPLICATION_ACCEPT,
            NotificationType.ARTIST_VENUE_LINK_APPLICATION_REJECT,NotificationType.EVENT_PERFORMER_APPROVAL_REQUESTED,
            NotificationType.EVENT_PERFORMER_APPROVED,NotificationType.EVENT_PERFORMER_REJECTED,
            NotificationType.VENUE_APPLICATION_APPROVED,NotificationType.VENUE_APPLICATION_REJECTED,
            NotificationType.STUDIO_RESERVATION_CREATED,NotificationType.STUDIO_RESERVATION_CONFLICTING_REQUESTS,
            NotificationType.STUDIO_RESERVATION_APPROVED,NotificationType.STUDIO_RESERVATION_REJECTED,
            NotificationType.STUDIO_RESERVATION_CANCELLED_BY_CUSTOMER,NotificationType.STUDIO_RESERVATION_CANCELLED_BY_STUDIO));

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

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void actualFollowServicesCommitRollbackAndSqlFailureAreAtomic(boolean band) {
        Domain d=domain(band);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> { d.follow(); status.setRollbackOnly(); });
        assertThat(domainCount(band)).isZero(); assertThat(rows.count()).isZero();
        jdbc.execute("create function fail_follow_intent() returns trigger language plpgsql as $$ begin raise exception 'fixture failure'; end $$; create trigger fail_follow_intent before insert on tbl_follow_notification_outbox for each row execute function fail_follow_intent()");
        try {
            assertThatThrownBy(d::follow).isInstanceOf(RuntimeException.class);
            assertThat(domainCount(band)).isZero(); assertThat(rows.count()).isZero();
        } finally { jdbc.execute("drop trigger fail_follow_intent on tbl_follow_notification_outbox; drop function fail_follow_intent()"); }
        d.follow();
        assertThat(domainCount(band)).isEqualTo(1); assertThat(rows.count()).isEqualTo(band?2:1);
        assertThat(rows.findAll()).allSatisfy(row -> { assertThat(row.getStatus()).isEqualTo(PENDING); assertThat(row.getAttemptCount()).isZero(); });
        assertThatThrownBy(d::follow).isInstanceOf(RuntimeException.class);
        assertThat(rows.count()).isEqualTo(band?2:1);
        var original=rows.findAll().stream().map(FollowNotificationOutbox::getEventId).toList();
        d.unfollow(); assertThat(rows.count()).isEqualTo(band?2:1);
        d.follow(); assertThat(rows.count()).isEqualTo(band?4:2);
        assertThat(rows.findAll()).extracting(FollowNotificationOutbox::getEventId).containsAll(original).doesNotHaveDuplicates();
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void personAndBandReachOneInboxPerRecipientWithPushEitherSetting(boolean enabled) {
        pushProperties.setEnabled(enabled);
        Domain person=domain(false),band=domain(true); person.follow(); band.follow();
        var expected=rows.findAll(); assertThat(expected).hasSize(3);
        listeners.start();
        workers.reject=false; scheduler.dispatchDue(); workers.runAll();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(inbox.count()).isEqualTo(3));
        assertThat(receipts.count()).isEqualTo(3);
        for(var row:expected) assertThat(inbox.findAll().stream().filter(n -> n.getSourceEventId().equals(row.getEventId())).toList())
            .singleElement().satisfies(n -> { assertThat(n.getRecipientId()).isEqualTo(row.getRecipientId()); assertThat(n.getOccurredAt()).isEqualTo(row.getOccurredAt()); });
        assertThat(jdbc.queryForObject("select count(*) from tbl_push_delivery",Integer.class)).isZero();
    }

    @Test void actualPersonAndBandFollowReachReceiptInboxV5JobAndStubTransport() throws Exception {
        // This entire context is owned Testcontainers PG/Rabbit. No shared DB,
        // external FCM, or API credentials are used.
        jdbc.execute("drop table tbl_push_delivery");
        for(String migration:List.of("2026-09-22-push-delivery-foundation","2026-09-23-push-device-registration-revision",
                "2026-09-24-push-native-venue-capability","2026-09-24-venue-application-notifications",
                "2026-09-24-push-native-studio-capability","2026-09-28-push-native-follow-capability"))
            jdbc.execute(Files.readString(Path.of("scripts/db/"+migration+".sql")));
        pushProperties.setEnabled(true); pushProperties.setAllowedTypes(FollowPushPresentation.TYPES);
        pushProperties.setTokenEncryptionKey(Base64.getEncoder().encodeToString(new byte[32]));
        var sql=new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc);
        var clock=java.time.Clock.fixed(START,java.time.ZoneOffset.UTC);
        var cipher=new PushTokenCipher(pushProperties);
        var fence=new com.berkayb.soundconnect.modules.user.support.AccountDeliveryFence(sql);
        var devices=new PushDeviceService(sql,fence,cipher,pushProperties,clock);
        var realPolicy=new NotificationDeliveryPolicy(fence,sql,transactionManager,mock(com.berkayb.soundconnect.modules.notification.service.AfterCommitDeliveryExecutor.class));
        var store=new PushDeliveryStore(sql,inbox,realPolicy,cipher,pushProperties,clock);
        Domain person=domain(false),band=domain(true);
        for(UUID recipient:List.of(person.recipient.getId(),band.recipient.getId(),band.sibling.getId())) {
            for(String version:List.of("ANDROID_NATIVE_V4",FollowPushPresentation.CAPABILITY)) {
                var device=UUID.randomUUID();
                tx(()->{devices.register(recipient,device,new PushDeviceService.Registration("fixture-"+device,
                    PushDeviceService.Platform.ANDROID,PushDeviceService.Permission.AUTHORIZED,"test",1L,version));return null;});
            }
        }
        person.follow();band.follow();var accepted=rows.findAll();assertThat(accepted).hasSize(3);
        // Unfollow and membership departure do not erase a historical occurrence.
        person.unfollow();jdbc.update("delete from tbl_band_member where band_id=? and user_id=?",band.band,band.sibling.getId());
        listeners.start();accepted.forEach(row->dispatcher.dispatch(row.getEventId()));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(()->{
            assertThat(inbox.count()).isEqualTo(3);assertThat(receipts.count()).isEqualTo(3);
            assertThat(jdbc.queryForObject("select count(*) from tbl_push_delivery",Integer.class)).isEqualTo(3);
        });
        assertThat(jdbc.queryForObject("select count(*) from tbl_push_delivery j join tbl_push_device d using(installation_id) where d.presentation_version='ANDROID_NATIVE_V4'",Integer.class)).isZero();
        var submitted=new ArrayList<com.berkayb.soundconnect.modules.notification.push.transport.PushEnvelope>();
        com.berkayb.soundconnect.modules.notification.push.transport.PushTransport stub=e->{submitted.add(e);return com.berkayb.soundconnect.modules.notification.push.transport.PushSendResult.accepted("fixture-accepted");};
        for(int i=0;i<3;i++) {
            var job=tx(()->store.claimNext().orElseThrow());var wire=tx(()->store.prepare(job).orElseThrow());
            var result=stub.send(wire);tx(()->{store.complete(job,result,PushTokenCipher.hash(wire.token()));return null;});
        }
        assertThat(submitted).hasSize(3).allSatisfy(e->{assertThat(e.data()).hasSize(7).containsEntry("presentationVersion",FollowPushPresentation.VERSION);assertThat(e.data().toString()).doesNotContain("private-avatar");});
        assertThat(jdbc.queryForObject("select count(*) from tbl_push_delivery where status='ACCEPTED' and attempt_count=1",Integer.class)).isEqualTo(3);
        assertThat(inbox.findAll()).allSatisfy(n->assertThat(n.isRead()).isFalse());
        // Direct replay through the real listener/receipt must not duplicate jobs.
        for(var n:inbox.findAll()) tx(()->{events.publishEvent(new NotificationPersisted(n));return null;});
        assertThat(jdbc.queryForObject("select count(*) from tbl_push_delivery",Integer.class)).isEqualTo(3);
    }

    @Test void rejectedExecutorAndRecreatedCoordinatorRecoverPendingWork() {
        var d=domain(true); d.follow(); scheduler.dispatchDue();
        assertThat(coordinator.scheduledCount()).isZero();
        assertThat(rows.countByStatus(PENDING)).isEqualTo(2);
        var recreated=new FollowNotificationOutboxScheduler(outbox,new FollowNotificationOutboxDispatcher(outbox,publisher),
            new FollowNotificationDispatchCoordinator(Runnable::run),properties);
        listeners.start(); recreated.dispatchDue();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(inbox.count()).isEqualTo(2));
    }

    @Test void idempotentEnqueuePreservesOriginalTimestampAndPublishedState() {
        var event=event(); enqueue(event); dispatcher.dispatch(event.eventId());
        new TransactionTemplate(transactionManager).executeWithoutResult(st -> outbox.enqueue(event.occurrenceId(),event.followerId(),event.recipientId(),null,START.plusSeconds(20)));
        assertThat(rows.count()).isEqualTo(1); assertThat(row(event).getStatus()).isEqualTo(PUBLISHED);
        assertThat(row(event).getOccurredAt()).isEqualTo(START); assertThat(row(event).getAttemptCount()).isEqualTo(1);
        var message=rabbit.receive(QUEUE,3000); assertThat(message).isNotNull();
        assertThat(message.getMessageProperties().getReceivedDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(message.getMessageProperties().getCorrelationId()).isEqualTo(event.eventId().toString());
    }

    @Test void unroutableConfirmedPublishStaysPendingUntilSchedulerRecoversIt() {
        var event = event();
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

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void stoppedDisposableBrokerAllowsActualFollowCommitAndRecreatedWorkersRecoverIt(boolean band) throws Exception {
        var d=domain(band);
        assertThat(RABBIT.execInContainer("rabbitmqctl", "stop_app").getExitCode()).isZero();
        try {
            connections.resetConnection();
            d.follow();
            assertThat(domainCount(band)).isEqualTo(1);
            rows.findAll().forEach(row -> dispatcher.dispatch(row.getEventId()));
            assertThat(rows.countByStatus(PENDING)).isEqualTo(band?2:1);
            assertThat(rows.findAll()).allSatisfy(row -> assertThat(row.getAttemptCount()).isEqualTo(1));
        } finally {
            assertThat(RABBIT.execInContainer("rabbitmqctl", "start_app").getExitCode()).isZero();
            connections.resetConnection();
            ((org.springframework.amqp.rabbit.core.RabbitAdmin) admin).initialize();
        }
        time.set(START.plusSeconds(5));
        listeners.start();
        new FollowNotificationOutboxScheduler(outbox,new FollowNotificationOutboxDispatcher(outbox,publisher),
            new FollowNotificationDispatchCoordinator(Runnable::run),properties).dispatchDue();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(inbox.count()).isEqualTo(band?2:1));
        assertThat(receipts.count()).isEqualTo(band?2:1);
        assertThat(rows.countByStatus(PUBLISHED)).isEqualTo(band?2:1);
    }

    @Test void leaseRecoveryRejectsStaleOwnerSuccessAndFailureUpdates() {
        var event = event();
        enqueue(event);
        var abandoned = outbox.claim(event.eventId(), "abandoned-worker").orElseThrow();
        time.set(START.plusSeconds(31));
        assertThat(outbox.markPublished(abandoned)).isFalse();
        assertThat(outbox.markFailed(abandoned, "java.lang.IllegalStateException"))
                .isEqualTo(FollowNotificationOutboxService.FailureDisposition.LEASE_LOST);
        var recovered = outbox.claim(event.eventId(), "new-worker").orElseThrow();
        assertThat(outbox.markPublished(abandoned)).isFalse();
        assertThat(outbox.markFailed(abandoned, "java.lang.IllegalStateException"))
                .isEqualTo(FollowNotificationOutboxService.FailureDisposition.LEASE_LOST);
        assertThat(row(event).getLeaseOwner()).isEqualTo("new-worker");
        assertThat(outbox.markPublished(recovered)).isTrue();
        assertThat(row(event).getAttemptCount()).isEqualTo(2);
    }

    @Test void brokerConfirmedThenAbandonedLeaseReplaysOnceThroughRealReceiptAndInbox() {
        var event = event();
        enqueue(event);
        var abandoned = outbox.claim(event.eventId(), "crashed-after-confirm").orElseThrow();
        listeners.start();
        publisher.publish(abandoned);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(consumerCompletions).containsExactly(TransactionSynchronization.STATUS_COMMITTED);
            assertThat(inbox.count()).isEqualTo(1);
        });
        // Model the post-confirm crash window by abandoning the claim; no JVM is killed.
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
        publisher.publish(abandoned);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(consumerCompletions).containsExactly(
                TransactionSynchronization.STATUS_COMMITTED, TransactionSynchronization.STATUS_COMMITTED,
                TransactionSynchronization.STATUS_COMMITTED));
        verify(policy, times(3)).eligible(any());
        assertThat(inbox.count()).isZero();
        assertThat(receipts.count()).isEqualTo(1);
        verify(policy, times(1)).schedule(any(), any(), any());
    }

    @Test void simultaneousWorkersHaveExactlyOneDatabaseLeaseWinner() throws Exception {
        var event = event();
        enqueue(event);
        int competitors = 12;
        var barrier = new CyclicBarrier(competitors);
        try (var executor = Executors.newFixedThreadPool(competitors)) {
            var tasks = new ArrayList<Future<Optional<FollowNotificationOutboxClaim>>>();
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
        var event = event();
        properties.setMaxAttempts(3);
        properties.setRetryMaxDelay(Duration.ofSeconds(8));
        enqueue(event);
        var first = outbox.claim(event.eventId(), "attempt-1").orElseThrow();
        assertThat(outbox.markFailed(first, "org.springframework.amqp.AmqpException"))
                .isEqualTo(FollowNotificationOutboxService.FailureDisposition.RETRY_SCHEDULED);
        assertThat(row(event).getNextAttemptAt()).isEqualTo(START.plusSeconds(5));
        assertThat(outbox.claim(event.eventId(), "too-early")).isEmpty();
        time.set(START.plusSeconds(5));
        var second = outbox.claim(event.eventId(), "attempt-2").orElseThrow();
        outbox.markFailed(second, "org.springframework.amqp.AmqpException");
        assertThat(row(event).getNextAttemptAt()).isEqualTo(START.plusSeconds(13));
        time.set(START.plusSeconds(13));
        var third = outbox.claim(event.eventId(), "attempt-3").orElseThrow();
        assertThat(outbox.markFailed(third, "org.springframework.amqp.AmqpException"))
                .isEqualTo(FollowNotificationOutboxService.FailureDisposition.DEAD_LETTER);
        time.set(START.plus(Duration.ofDays(30)));
        assertThat(outbox.findDueEventIds(100)).isEmpty();
        assertThat(outbox.claim(event.eventId(), "attempt-4")).isEmpty();
        assertThat(row(event).getStatus()).isEqualTo(DEAD_LETTER);
        assertThat(row(event).getAttemptCount()).isEqualTo(3);
        assertThat(row(event).getFollowerId()).isEqualTo(event.followerId());
    }

    @Test void repeatedWorkerCrashesAlsoConsumeTheFiniteAttemptBudget() {
        var event = event();
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
        var published = event();
        var pending = event();
        var inflight = event();
        var dead = event();
        for (var event : List.of(published, pending, inflight, dead)) enqueue(event);
        outbox.markPublished(outbox.claim(published.eventId(), "published").orElseThrow());
        outbox.claim(inflight.eventId(), "inflight").orElseThrow();
        properties.setMaxAttempts(1);
        outbox.markFailed(outbox.claim(dead.eventId(), "dead").orElseThrow(), "org.springframework.amqp.AmqpException");
        assertThat(outbox.cleanupPublished()).isZero();
        time.set(START.plus(Duration.ofDays(8)));
        assertThat(outbox.cleanupPublished()).isEqualTo(1);
        assertThat(rows.findAll()).extracting(FollowNotificationOutbox::getEventId)
                .containsExactlyInAnyOrder(pending.eventId(), inflight.eventId(), dead.eventId());
    }

    @Test void replayCannotClearAnActiveLeaseOrBypassItsRetryDelay() {
        var event = event();
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
        var pending = new ArrayList<Intent>();
        for (int i = 0; i < 12; i++) {
            var event = event();
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
    @Test void unavailableActorRecipientAndDeletedBandAreExplicitlySuppressed() {
        for(boolean actor:List.of(false,true)) {
            var event=event(); enqueue(event);
            jdbc.update("update tbl_user set erased_at=now() where id=?",actor?event.followerId():event.recipientId());
            dispatcher.dispatch(event.eventId());
            assertThat(row(event).getStatus()).isEqualTo(SUPPRESSED);
            assertThat(row(event).getLastErrorType()).isEqualTo("SourceUnavailable");
        }
        var d=domain(true); d.follow(); var ids=rows.findAll().stream().filter(x -> x.getBandId()!=null).map(FollowNotificationOutbox::getEventId).toList();
        jdbc.update("delete from tbl_band_follow where band_id=?",d.band);
        jdbc.update("delete from tbl_band_member where band_id=?",d.band);
        jdbc.update("delete from tbl_band where id=?",d.band);
        ids.forEach(dispatcher::dispatch);
        assertThat(rows.countByStatus(SUPPRESSED)).isEqualTo(4);
        assertThat(admin.getQueueInfo(QUEUE).getMessageCount()).isZero();
    }

    @Test void resolverFailureRetainsRetryThenCurrentGhostIdentityWinsWithoutOldNameOrAvatar() {
        Domain d=domain(false); d.follow(); var row=rows.findAll().getFirst();
        doThrow(new IllegalStateException("private fixture detail must not enter last_error_type")).when(identities).resolve(anyCollection());
        dispatcher.dispatch(row.getEventId());
        assertThat(rows.findById(row.getEventId()).orElseThrow().getStatus()).isEqualTo(PENDING);
        assertThat(admin.getQueueInfo(QUEUE).getMessageCount()).isZero();
        assertThat(rows.findById(row.getEventId()).orElseThrow().getLastErrorType()).isEqualTo(IllegalStateException.class.getName());
        org.mockito.Mockito.reset(identities);
        tx(() -> { em.persist(ListenerProfile.builder().user(em.find(User.class,d.actor.getId())).name("PRIVATE_REAL_NAME")
            .visibilityChoiceCompleted(true).visibilityMode(ListenerVisibilityMode.GHOST).build()); return null; });
        time.set(START.plusSeconds(5)); dispatcher.dispatch(row.getEventId());
        var message=rabbit.receive(QUEUE,3000); assertThat(message).isNotNull();
        String body=new String(message.getBody(),java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body).contains("GHOST",d.actor.getUsername()).doesNotContain("PRIVATE_REAL_NAME","private-avatar");
        verify(publicProfiles,never()).resolveByUserId(d.actor.getId());
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"NACK","TIMEOUT"})
    void actualConfirmFailuresBecomeDurableRetry(String failure) {
        var transport=mock(RabbitTemplate.class);
        var config=new NotificationPublisherProperties();config.setPublisherConfirmTimeout(Duration.ofSeconds(1));
        var failedProducer=new NotificationProducer(transport,config);
        ReflectionTestUtils.setField(failedProducer,"exchange","fixture");
        ReflectionTestUtils.setField(failedProducer,"publishRoutingKey","fixture");
        doAnswer(inv -> {
            var correlation=inv.<org.springframework.amqp.rabbit.connection.CorrelationData>getArgument(4);
            if(failure.equals("NACK")) correlation.getFuture().complete(new org.springframework.amqp.rabbit.connection.CorrelationData.Confirm(false,"fixture"));
            return null;
        }).when(transport).convertAndSend(anyString(),anyString(),any(NotificationInboundEvent.class),
                any(org.springframework.amqp.core.MessagePostProcessor.class),any(org.springframework.amqp.rabbit.connection.CorrelationData.class));
        doAnswer(inv -> { failedProducer.publishConfirmed(inv.getArgument(0));return null; }).when(producer).publishConfirmed(any());
        var e=event();enqueue(e);dispatcher.dispatch(e.eventId());
        assertThat(row(e).getStatus()).isEqualTo(PENDING);
        assertThat(row(e).getLastErrorType()).isEqualTo("org.springframework.amqp.AmqpException");
        assertThat(row(e).getNextAttemptAt()).isEqualTo(START.plusSeconds(5));
        assertThat(admin.getQueueInfo(QUEUE).getMessageCount()).isZero();
    }

    @Test void actualAckThenDatabaseMarkFailureReplaysWithoutResettingReadOrResurrectingDeletedInbox() {
        var d=domain(false);d.follow();var id=rows.findAll().getFirst().getEventId();listeners.start();
        jdbc.execute("create function fail_follow_mark() returns trigger language plpgsql as $$ begin if NEW.status='PUBLISHED' then raise exception 'fixture mark failure'; end if; return NEW; end $$; create trigger fail_follow_mark before update on tbl_follow_notification_outbox for each row execute function fail_follow_mark()");
        try { assertThatThrownBy(() -> dispatcher.dispatch(id)).isInstanceOf(RuntimeException.class); }
        finally {jdbc.execute("drop trigger fail_follow_mark on tbl_follow_notification_outbox; drop function fail_follow_mark()");}
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(inbox.count()).isEqualTo(1));
        assertThat(rows.findById(id).orElseThrow().getStatus()).isEqualTo(IN_FLIGHT);
        jdbc.update("update tbl_notification set is_read=true where source_event_id=?",id);
        time.set(START.plusSeconds(31));dispatcher.dispatch(id);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(consumerCompletions).hasSize(2));
        assertThat(receipts.count()).isEqualTo(1);assertThat(inbox.findAll().getFirst().isRead()).isTrue();
        inbox.deleteAll();
        var row=rows.findById(id).orElseThrow();
        publisher.publish(new FollowNotificationOutboxClaim(id,row.getOccurrenceId(),row.getFollowerId(),row.getRecipientId(),null,row.getNotificationType(),row.getOccurredAt(),2,"redelivery"));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(consumerCompletions).hasSize(3));
        assertThat(inbox.count()).isZero();assertThat(receipts.count()).isEqualTo(1);
    }

    @Test void bandSnapshotAndPartialFanoutRetryDoNotAddLateMembersOrDuplicateSuccessfulSibling() {
        var d=domain(true);d.follow();var original=rows.findAll();User late=user();
        tx(() -> {em.persist(BandMember.builder().band(em.find(Band.class,d.band)).user(em.find(User.class,late.getId()))
            .bandRole(com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandRole.MEMBER)
            .status(BandMemberShipStatus.ACTIVE).build());return null;});
        // An accepted member remains in the historical snapshot even after leaving.
        jdbc.update("delete from tbl_band_member where band_id=? and user_id=?",d.band,d.sibling.getId());
        doAnswer(inv -> {NotificationInboundEvent e=inv.getArgument(0);
            if(e.recipientId().equals(d.sibling.getId())) throw new org.springframework.amqp.AmqpException("fixture");
            return inv.callRealMethod();}).when(producer).publishConfirmed(any());
        listeners.start();original.forEach(row -> dispatcher.dispatch(row.getEventId()));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(inbox.count()).isEqualTo(1));
        assertThat(rows.countByStatus(PENDING)).isEqualTo(1);assertThat(rows.countByStatus(PUBLISHED)).isEqualTo(1);
        reset(producer);time.set(START.plusSeconds(5));workers.reject=false;scheduler.dispatchDue();workers.runAll();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(inbox.count()).isEqualTo(2));
        assertThat(inbox.findAll()).extracting(Notification::getRecipientId).containsExactlyInAnyOrder(d.recipient.getId(),d.sibling.getId());
        assertThat(receipts.count()).isEqualTo(2);assertThat(rows.count()).isEqualTo(2);
    }

    @Test void realSelfActiveBandMemberAndGhostTargetRulesLeaveNoIntent() {
        var d=domain(true);
        assertThatThrownBy(() -> follows.follow(d.actor,d.actor)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> bandFollows.followBand(d.recipient.getId(),d.band)).isInstanceOf(RuntimeException.class);
        tx(() -> {em.persist(ListenerProfile.builder().user(em.find(User.class,d.recipient.getId()))
            .visibilityChoiceCompleted(true).visibilityMode(ListenerVisibilityMode.GHOST).build());return null;});
        assertThatThrownBy(() -> follows.follow(d.actor,d.recipient)).isInstanceOf(RuntimeException.class);
        assertThat(rows.count()).isZero();assertThat(domainCount(false)).isZero();assertThat(domainCount(true)).isZero();
    }

    @Test void currentVisibilityReadLockIsHeldThroughBrokerPublishWithoutNestedTransaction() throws Exception {
        var d=domain(false);tx(() -> {em.persist(ListenerProfile.builder().user(em.find(User.class,d.actor.getId()))
            .visibilityChoiceCompleted(true).visibilityMode(ListenerVisibilityMode.STANDARD).build());return null;});
        d.follow();var id=rows.findAll().getFirst().getEventId();
        var publishing=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(inv -> {assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(CountingDataSource.activeOnThread.get()).isEqualTo(1);
            publishing.countDown();assertThat(release.await(10,TimeUnit.SECONDS)).isTrue();return inv.callRealMethod();
        }).when(producer).publishConfirmed(any());
        try(var threads=Executors.newFixedThreadPool(2)) {
            var worker=threads.submit(() -> dispatcher.dispatch(id));assertThat(publishing.await(10,TimeUnit.SECONDS)).isTrue();
            var writer=threads.submit(() -> jdbc.update("update \"tbl_listener-profile\" set visibility_mode='GHOST' where user_id=?",d.actor.getId()));
            try {
                await().atMost(Duration.ofSeconds(5)).until(() -> jdbc.queryForObject("select count(*) from pg_stat_activity where datname=current_database() and wait_event_type='Lock' and query like 'update%tbl_listener-profile%'",Integer.class)>0);
                assertThat(writer.isDone()).isFalse();
            } finally {release.countDown();}
            worker.get(10,TimeUnit.SECONDS);assertThat(writer.get(10,TimeUnit.SECONDS)).isEqualTo(1);
        } finally {release.countDown();}
    }

    private record Intent(UUID eventId,UUID occurrenceId,UUID followerId,UUID recipientId,Instant occurredAt) { }
    private Intent event() {
        UUID actor=user().getId(),recipient=user().getId(),occurrence=UUID.randomUUID();
        return new Intent(FollowNotificationOutboxService.eventId(occurrence,recipient,NotificationType.SOCIAL_NEW_FOLLOWER),occurrence,actor,recipient,START);
    }
    private void enqueue(Intent e) { tx(() -> { outbox.enqueue(e.occurrenceId(),e.followerId(),e.recipientId(),null,e.occurredAt()); return null; }); }
    private FollowNotificationOutbox row(Intent e) { return rows.findById(e.eventId()).orElseThrow(); }
    private long domainCount(boolean band) { return jdbc.queryForObject("select count(*) from "+(band?"tbl_band_follow":"tbl_follow"),Long.class); }
    private <T>T tx(java.util.function.Supplier<T> work) { return new TransactionTemplate(transactionManager).execute(st -> work.get()); }
    private User user() { return tx(() -> {
        var u=User.builder().username("u"+UUID.randomUUID().toString().replace("-","").substring(0,16))
            .email(UUID.randomUUID()+"@test.invalid").password("fixture-only").status(UserStatus.ACTIVE).emailVerified(true)
            .publicCode("SC-"+UUID.randomUUID().toString().replace("-","").substring(0,20)).profilePicture("private-avatar").build();
        em.persist(u); return u;
    }); }
    private Domain domain(boolean isBand) {
        User actor=user(),recipient=user(),sibling=user();
        UUID band=isBand?tx(() -> {
            Band b=Band.builder().name("fixture-"+UUID.randomUUID()).build();em.persist(b);
            for(User u:List.of(recipient,sibling)) em.persist(BandMember.builder().band(b).user(em.find(User.class,u.getId())).bandRole(com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandRole.MEMBER).status(BandMemberShipStatus.ACTIVE).build());
            return b.getId();
        }):null;
        return new Domain(actor,recipient,sibling,band);
    }
    private class Domain {
        final User actor,recipient,sibling;final UUID band;
        Domain(User a,User r,User s,UUID b){actor=a;recipient=r;sibling=s;band=b;}
        void follow(){if(band==null) follows.follow(actor,recipient);else bandFollows.followBand(actor.getId(),band);}
        void unfollow(){if(band==null) follows.unfollow(actor,recipient);else bandFollows.unfollowBand(actor.getId(),band);}
    }

    /** Checks the worker does not borrow a second connection while holding its visibility transaction. */
    static class CountingDataSource extends org.springframework.jdbc.datasource.DelegatingDataSource {
        static final ThreadLocal<Integer> activeOnThread=ThreadLocal.withInitial(() -> 0);
        CountingDataSource(DataSource delegate){super(delegate);}
        @Override public java.sql.Connection getConnection() throws java.sql.SQLException {
            var connection=super.getConnection();activeOnThread.set(activeOnThread.get()+1);
            var closed=new java.util.concurrent.atomic.AtomicBoolean();
            return (java.sql.Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{java.sql.Connection.class},(proxy,method,args) -> {
                    try {return method.invoke(connection,args);}
                    catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
                    finally {if(method.getName().equals("close") && closed.compareAndSet(false,true)) activeOnThread.set(activeOnThread.get()-1);}
                });
        }
    }

    static class MutableTime extends FollowNotificationOutboxTimeProvider {
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
