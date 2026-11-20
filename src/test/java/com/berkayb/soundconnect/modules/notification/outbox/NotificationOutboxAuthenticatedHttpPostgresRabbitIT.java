package com.berkayb.soundconnect.modules.notification.outbox;

import com.berkayb.soundconnect.auth.ratelimit.*;
import com.berkayb.soundconnect.auth.security.*;
import com.berkayb.soundconnect.auth.service.CustomUserDetailsService;
import com.berkayb.soundconnect.modules.application.venueapplication.service.VenueApplicationSessionAccess;
import com.berkayb.soundconnect.modules.event.mapper.EventMapper;
import com.berkayb.soundconnect.modules.event.performer.outbox.*;
import com.berkayb.soundconnect.modules.event.performer.service.EventPerformerRequestServiceImpl;
import com.berkayb.soundconnect.modules.event.plan.*;
import com.berkayb.soundconnect.modules.event.support.*;
import com.berkayb.soundconnect.modules.location.entity.*;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.notification.config.NotificationRabbitConfig;
import com.berkayb.soundconnect.modules.notification.controller.user.NotificationController;
import com.berkayb.soundconnect.modules.notification.helper.NotificationBadgeCacheHelper;
import com.berkayb.soundconnect.modules.notification.listener.NotificationEventListener;
import com.berkayb.soundconnect.modules.notification.mapper.NotificationMapper;
import com.berkayb.soundconnect.modules.notification.service.*;
import com.berkayb.soundconnect.modules.notification.websocket.NotificationWebSocketService;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.support.ListenerProfileChoiceStatusReader;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.support.BandRepresentationPolicy;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.modules.profile.shared.identity.GhostListenerIdentityBatchResolver;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.modules.user.support.AccountDeliveryFence;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import com.berkayb.soundconnect.modules.venue.enums.VenueStatus;
import com.berkayb.soundconnect.shared.config.SecurityConfig;
import com.berkayb.soundconnect.shared.exception.GlobalExceptionHandler;
import com.berkayb.soundconnect.shared.mail.producer.MailProducer;
import com.berkayb.soundconnect.shared.messaging.events.notification.*;
import com.berkayb.soundconnect.shared.security.*;
import com.berkayb.soundconnect.shared.util.JwtUtil;
import com.fasterxml.jackson.databind.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.*;
import org.testcontainers.containers.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.junit.jupiter.Container;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Real loopback TCP, signed JWT, DB-backed user/role/ownership checks and normal
 * plan controller/domain -> outbox -> confirmed Rabbit -> real eligible receipt/inbox.
 * Tokens use the product issuer with a fresh fixture-only key (no login/OTP claim).
 * Only media decoration, outbound mail and websocket delivery are test doubles. */
@Testcontainers
@SpringBootTest(classes = NotificationOutboxAuthenticatedHttpPostgresRabbitIT.Fixture.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.location=optional:classpath:/bil004-http-isolated-test.yml", "spring.config.import=",
        "server.address=127.0.0.1", "spring.main.web-application-type=servlet",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.open-in-view=false",
        "spring.rabbitmq.publisher-confirm-type=correlated", "spring.rabbitmq.publisher-returns=true",
        "spring.rabbitmq.template.mandatory=true", "spring.rabbitmq.connection-timeout=2s",
        "app.messaging.notification.exchange=bil004.http.exchange", "app.messaging.notification.queue=bil004.http.queue",
        "app.messaging.notification.routingKey=notification.#", "app.messaging.notification.dlxExchange=bil004.http.dlx",
        "app.messaging.notification.dlq=bil004.http.dlq", "app.messaging.notification.publisher-confirm-timeout=2s",
        "app.jwt.expiration=3600000", "app.jwt.issuer=bil004-isolated",
        "app.share-base-url=https://bil004.invalid", "app.notification.push.enabled=false",
        "logging.level.org.hibernate.SQL=OFF", "logging.level.org.hibernate.orm.jdbc.bind=OFF"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class NotificationOutboxAuthenticatedHttpPostgresRabbitIT {
    static final String OWNER = "bil004-authenticated-http";
    static final String KEY = UUID.randomUUID().toString() + UUID.randomUUID();
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("bil004_http").withLabel("soundconnect.fixture", OWNER).withReuse(false);
    @Container static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-alpine")
            .withLabel("soundconnect.fixture", OWNER).withReuse(false);
    // The normal plan quota and badge use their own Redis; never the shared product Redis.
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.2.5-alpine")
            .withExposedPorts(6379).withLabel("soundconnect.fixture", OWNER).withReuse(false);

    @DynamicPropertySource static void services(DynamicPropertyRegistry r) {
        r.add("spring.rabbitmq.host", RABBIT::getHost); r.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        r.add("spring.rabbitmq.username", RABBIT::getAdminUsername); r.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
        r.add("spring.data.redis.host", REDIS::getHost); r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        r.add("app.jwt.secret", () -> KEY);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EntityScan("com.berkayb.soundconnect")
    @EnableJpaRepositories("com.berkayb.soundconnect")
    @EnableTransactionManagement(proxyTargetClass = true)
    @Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtTokenProvider.class,
            CustomUserDetailsService.class, JwtUtil.class, ListenerProfileChoiceGate.class,
            ListenerProfileChoiceStatusReader.class, VenueApplicationSessionAccess.class,
            AuthRateLimitConfiguration.class, AuthRateLimiter.class, SecurityErrorResponseWriter.class,
            RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class, GlobalExceptionHandler.class,
            EventPlanOwnerController.class, EventPlanService.class, EventPlanRateGuard.class,
            EventPlanPrivateResponseFilter.class, EventMapper.class, EventScheduleClock.class,
            EventShareUrlBuilder.class, BandRepresentationPolicy.class, EventPerformerRequestServiceImpl.class,
            EventPerformerNotificationOutboxService.class, EventPerformerNotificationOutboxDispatcher.class,
            EventPerformerNotificationOutboxPublisher.class, EventPerformerNotificationDispatchCoordinator.class,
            EventPerformerNotificationOutboxConfiguration.class, EventPerformerNotificationOutboxTimeProvider.class,
            NotificationRabbitConfig.class, NotificationEventListener.class, NotificationController.class,
            NotificationServiceImpl.class, NotificationDeliveryPolicy.class, AccountDeliveryFence.class,
            AfterCommitDeliveryExecutor.class, GhostListenerIdentityBatchResolver.class,
            NotificationBadgeCacheHelper.class, com.berkayb.soundconnect.shared.config.JpaAuditingConfig.class})
    static class Fixture {
        static void dataSourceIdentityGuard() {
            assertThat(POSTGRES.isRunning()).isTrue();
            assertThat(POSTGRES.getContainerInfo().getConfig().getLabels()).containsEntry("soundconnect.fixture", OWNER);
            assertThat(POSTGRES.getDatabaseName()).isEqualTo("bil004_http");
        }
        @Bean DataSource dataSource() throws Exception {
            assertThat(POSTGRES.getContainerInfo().getConfig().getLabels()).containsEntry("soundconnect.fixture", OWNER);
            var ds = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
            try (var c = ds.getConnection()) {
                assertThat(c.getMetaData().getURL()).isEqualTo(POSTGRES.getJdbcUrl());
                assertThat(c.getCatalog()).isEqualTo("bil004_http");
            }
            return ds;
        }
        @Bean NotificationProducer producer(RabbitTemplate rabbit, NotificationPublisherProperties p) { return new NotificationProducer(rabbit, p); }
        @Bean NotificationMapper mapper() { return org.mapstruct.factory.Mappers.getMapper(NotificationMapper.class); }
        @Bean Jackson2JsonMessageConverter converter(ObjectMapper mapper) { return new Jackson2JsonMessageConverter(mapper); }
    }

    @LocalServerPort int port;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired JwtTokenProvider tokens;
    @MockitoSpyBean NotificationProducer producer;
    @MockitoSpyBean NotificationEventListener listener;
    @MockitoBean MediaAssetService media;
    @MockitoBean NotificationWebSocketService websocket;
    @MockitoBean MailProducer mail;
    final List<Integer> consumerCommits = new CopyOnWriteArrayList<>();
    final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Autowired org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry consumers;
    @Autowired RabbitTemplate rabbit;
    @Autowired EventPerformerNotificationOutboxService eventOutbox;
    @Autowired EventPerformerNotificationOutboxDispatcher eventDispatcher;
    @Autowired EventPerformerNotificationOutboxRepository eventRows;
    @Autowired NotificationDeliveryPolicy deliveryPolicy;

    @Autowired org.springframework.data.redis.core.StringRedisTemplate badgeRedis;
    @Autowired NotificationBadgeCacheHelper badgeCache;

    @Test void emptyRedisRebuildsBadgeFromCurrentDatabaseOverAuthenticatedHttp() throws Exception {
        Fixture.dataSourceIdentityGuard();
        assertThat(REDIS.getContainerInfo().getConfig().getLabels()).containsEntry("soundconnect.fixture", OWNER);
        assertThat(badgeRedis.getConnectionFactory()).isInstanceOf(
                org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory.class);
        var factory = (org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory) badgeRedis.getConnectionFactory();
        assertThat(factory.getPort()).isEqualTo(REDIS.getMappedPort(6379));
        assertThat(factory.getHostName()).isEqualTo(REDIS.getHost());
        assertThat(factory.getDatabase()).isZero();
        var actors = createActors();
        var ids = new TransactionTemplate(transactions).execute(status -> {
            var rows = new ArrayList<UUID>();
            for (UUID recipient : List.of(actors.musician, actors.musician, actors.stranger)) {
                var row = persist(com.berkayb.soundconnect.modules.notification.entity.Notification.builder()
                        .recipientId(recipient).type(com.berkayb.soundconnect.modules.notification.enums.NotificationType.AUTH_EMAIL_VERIFIED)
                        .title("Recovery fixture").message("Local only").occurredAt(Instant.now()).build());
                rows.add(row.getId());
            }
            return rows;
        });
        String path = "/api/v1/user/notifications";
        badgeCache.setUnread(actors.musician, 999);
        assertThat(request("GET", path + "/unread-count", actors.musicianToken, null, 200).at("/data/unread").asLong()).isEqualTo(2);
        assertThat(badgeCache.getCacheUnread(actors.musician)).isEqualTo(2L);
        try (var connection = badgeRedis.getConnectionFactory().getConnection()) {
            connection.serverCommands().flushDb();
            assertThat(connection.serverCommands().dbSize()).isZero();
        }
        assertThat(jdbc.queryForObject("select count(*) from tbl_notification", Integer.class)).isEqualTo(3);
        assertThat(badgeCache.getCacheUnread(actors.musician)).isNull();
        assertThat(request("GET", path + "/unread-count", actors.musicianToken, null, 200).at("/data/unread").asLong()).isEqualTo(2);
        assertThat(badgeCache.getCacheUnread(actors.musician)).isEqualTo(2L);
        assertThat(badgeRedis.getExpire(badgeCache.unreadKey(actors.musician))).isBetween(1L, 900L);
        assertThat(request("GET", path + "/unread-count", actors.strangerToken, null, 200).at("/data/unread").asLong()).isEqualTo(1);
        request("POST", path + "/" + ids.getFirst() + "/read", actors.strangerToken, null, 404);
        assertThat(jdbc.queryForObject("select count(*) from tbl_notification where is_read", Integer.class)).isZero();
        // A cold cache on mutation must use the post-commit DB count, without a second decrement.
        badgeRedis.delete(badgeCache.unreadKey(actors.musician));
        request("POST", path + "/" + ids.getFirst() + "/read", actors.musicianToken, null, 200);
        assertThat(jdbc.queryForObject("select is_read from tbl_notification where id=?", Boolean.class, ids.getFirst())).isTrue();
        assertThat(badgeCache.getCacheUnread(actors.musician)).isEqualTo(1L);
        request("POST", path + "/" + ids.getFirst() + "/read", actors.musicianToken, null, 200);
        assertThat(request("GET", path + "/unread-count", actors.musicianToken, null, 200).at("/data/unread").asLong()).isEqualTo(1);
        badgeRedis.delete(badgeCache.unreadKey(actors.musician));
        request("DELETE", path + "/" + ids.get(1), actors.musicianToken, null, 200);
        assertThat(badgeCache.getCacheUnread(actors.musician)).isZero();
        assertThat(request("GET", path + "/unread-count", actors.musicianToken, null, 200).at("/data/unread").asLong()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from tbl_notification where recipient_id=? and not is_read", Integer.class, actors.musician)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from tbl_notification where recipient_id=? and not is_read", Integer.class, actors.stranger)).isEqualTo(1);
        request("GET", path + "/unread-count", null, null, 401);
        com.berkayb.soundconnect.support.RecoveryCacheMailEvidence.write("badge-http", Map.of(
                "postgresContainer", POSTGRES.getContainerId(), "redisContainer", REDIS.getContainerId(),
                "rabbitContainer", RABBIT.getContainerId(), "apiPort", port,
                "httpChecks", 10, "staleCacheIgnored", true, "rebuiltUnread", 2,
                "coldCacheReadThenDelete", "2 -> 1 -> 0", "otherRecipientUnread", 1,
                "websocketDeliveryIsTestDouble", true));
    }

    /** An owned queue purge models loss of a confirmed broker payload. It is not
     * a staging outage, full broker disk-loss rehearsal, or production replay tool.
     * The exact test-side replay is deliberately separate from normal dispatch. */
    @Test void recoveryFindsLostPublishedIntentAndPreservesDeletedReceiptAcrossActualDumpRestore() throws Exception {
        java.nio.file.Path evidence = java.nio.file.Path.of(System.getProperty("sc.recovery.evidenceDir",
                "build/recovery-runtime-verification/standalone-" + UUID.randomUUID()));
        assertThat(java.nio.file.Files.isRegularFile(evidence.resolve("inventory-queries.json"))).isTrue();
        Fixture.dataSourceIdentityGuard();
        assertThat(RABBIT.getContainerInfo().getConfig().getLabels()).containsEntry("soundconnect.fixture", OWNER);
        consumers.stop();
        assertThat(consumers.getListenerContainers()).isNotEmpty().allMatch(c -> !c.isRunning());
        assertThat(queueCount()).isZero();
        Instant observedAt = Instant.now();
        Actors actors = createActors();
        LocalDate day = LocalDate.now(ZoneId.of("Europe/Istanbul")).plusDays(2);
        var definition = new EventPlanDefinition(actors.venue, day, day, List.of(day.getDayOfWeek().getValue()), List.of(),
                new EventPlanTemplate("Recovery owned producer", null, LocalTime.of(20, 0), LocalTime.of(22, 0), null, actors.profile, null, null));
        request("POST", "/api/v1/venue-owner/event-plans", actors.ownerToken,
                json.writeValueAsString(new EventPlanCreateRequest(UUID.randomUUID(), definition)), 200);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(jdbc.queryForObject("select count(*) from tbl_event_performer_notification_outbox where status='PUBLISHED'", Integer.class)).isEqualTo(1);
            assertThat(queueCount()).isEqualTo(1);
        });
        UUID eventId = jdbc.queryForObject("select event_id from tbl_event_performer_notification_outbox", UUID.class);
        assertThat(jdbc.queryForObject("select count(*) from tbl_notification_receipt", Integer.class)).isZero();
        var captured = org.mockito.ArgumentCaptor.forClass(NotificationInboundEvent.class);
        verify(producer).publishConfirmed(captured.capture());
        String intentBefore = jdbc.queryForObject("select to_jsonb(o)::text from tbl_event_performer_notification_outbox o where event_id=?", String.class, eventId);
        // Only this fixture's sole stopped-consumer queue; assert one actual lost payload.
        Integer purged = rabbit.execute(channel -> channel.queuePurge("bil004.http.queue").getMessageCount());
        assertThat(purged).isEqualTo(1);
        assertThat(queueCount()).isZero();
        assertThat(eventOutbox.findDueEventIds(10)).doesNotContain(eventId);
        eventDispatcher.dispatch(eventId);
        verify(producer).publishConfirmed(any());
        assertThat(jdbc.queryForObject("select to_jsonb(o)::text from tbl_event_performer_notification_outbox o where event_id=?", String.class, eventId)).isEqualTo(intentBefore);
        assertThat(jdbc.queryForObject("select count(*) from tbl_notification", Integer.class)).isZero();
        jdbc.execute("CREATE ROLE recovery_reader NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS");
        inventory(evidence, "lost", POSTGRES.getJdbcUrl(), "bil004_http", actors.musician, eventId);

        // Local operator rehearsal: reconstruct the exact event from durable intent,
        // recheck real domain eligibility, and publish only that ID. No bulk status reset.
        var row = eventRows.findById(eventId).orElseThrow();
        var recovered = new NotificationInboundEvent(row.getEventId(), row.getRecipientId(), row.getNotificationType(),
                row.getTitle(), row.getMessage(), row.getPayload(), row.isEmailForce(), row.getOccurredAt());
        assertThat(recovered).usingRecursiveComparison().isEqualTo(captured.getValue());
        Boolean eligible = new TransactionTemplate(transactions).execute(status -> deliveryPolicy.eligible(recovered));
        assertThat(eligible).isTrue();
        producer.publishConfirmed(recovered);
        assertThat(queueCount()).isEqualTo(1);
        consumers.start();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(jdbc.queryForObject("select count(*) from tbl_notification_receipt where source_event_id=?", Integer.class, eventId)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from tbl_notification where source_event_id=?", Integer.class, eventId)).isEqualTo(1);
        });
        UUID notificationId = jdbc.queryForObject("select id from tbl_notification where source_event_id=?", UUID.class, eventId);
        String inboxPath = "/api/v1/user/notifications/" + notificationId;
        request("GET", inboxPath, actors.musicianToken, null, 200);
        request("DELETE", inboxPath, actors.musicianToken, null, 200);
        assertThat(jdbc.queryForObject("select count(*) from tbl_notification", Integer.class)).isZero();
        inventory(evidence, "deleted", POSTGRES.getJdbcUrl(), "bil004_http", actors.musician, eventId);
        producer.publishConfirmed(recovered);
        verify(listener, timeout(10000).times(2)).handle(any());
        consumers.stop();
        assertThat(jdbc.queryForObject("select count(*) from tbl_notification", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from tbl_notification_receipt", Integer.class)).isEqualTo(1);
        request("GET", inboxPath, actors.musicianToken, null, 404);
        verify(producer, times(3)).publishConfirmed(any());
        assertThat(jdbc.queryForObject("select to_jsonb(o)::text from tbl_event_performer_notification_outbox o where event_id=?", String.class, eventId)).isEqualTo(intentBefore);

        // Real PG custom dump -> distinct empty DB in this disposable owned container.
        pgCommand("pg_dump", "-U", POSTGRES.getUsername(), "-d", "bil004_http", "-Fc", "-f", "/tmp/recovery-receipt.dump");
        pgCommand("createdb", "-U", POSTGRES.getUsername(), "recovery_receipt_restored");
        pgCommand("pg_restore", "-U", POSTGRES.getUsername(), "--exit-on-error", "--no-owner", "--no-acl",
                "-d", "recovery_receipt_restored", "/tmp/recovery-receipt.dump");
        String restoredUrl = POSTGRES.getJdbcUrl().replace("/bil004_http", "/recovery_receipt_restored");
        assertThat(restoredUrl).isNotEqualTo(POSTGRES.getJdbcUrl());
        var restoredJdbc = new JdbcTemplate(new DriverManagerDataSource(restoredUrl, POSTGRES.getUsername(), POSTGRES.getPassword()));
        assertThat(restoredJdbc.queryForObject("select current_database()", String.class)).isEqualTo("recovery_receipt_restored");
        assertThat(restoredJdbc.queryForObject("select count(*) from tbl_notification", Integer.class)).isZero();
        assertThat(restoredJdbc.queryForObject("select recipient_id from tbl_notification_receipt where source_event_id=?", UUID.class, eventId)).isEqualTo(actors.musician);
        inventory(evidence, "restored", restoredUrl, "recovery_receipt_restored", actors.musician, eventId);
        var proof = new LinkedHashMap<String, Object>();
        proof.put("scope", "synthetic-owned-local-broker-loss"); proof.put("observedAt", observedAt.toString());
        proof.put("eventId", eventId); proof.put("recipientId", actors.musician);
        proof.put("postgresContainer", POSTGRES.getContainerId()); proof.put("rabbitContainer", RABBIT.getContainerId());
        proof.put("redisContainer", REDIS.getContainerId()); proof.put("lostConfirmedPayloads", 1);
        proof.put("normalRecoveryPublishes", 0); proof.put("controlledTestReplayPublishes", 2);
        proof.put("inboxAfterRestore", 0); proof.put("receiptAfterRestore", 1);
        proof.put("automaticProductionReplayImplemented", false); proof.put("providerDeliveryTested", false);
        writeEvidence(evidence.resolve("recovery-evidence.json"), proof);
        System.out.println("RECOVERY016_LOCAL publishedLoss=1 normalReplay=0 controlledReplays=2 restoredReceipt=1 restoredInbox=0 providerDelivery=NOT_TESTED");
    }

    int queueCount() {
        return rabbit.execute(channel -> channel.queueDeclarePassive("bil004.http.queue").getMessageCount());
    }

    void pgCommand(String... command) throws Exception {
        Fixture.dataSourceIdentityGuard();
        var args = new ArrayList<String>(List.of("timeout", "30")); args.addAll(List.of(command));
        var result = POSTGRES.execInContainer(args.toArray(String[]::new));
        assertThat(result.getExitCode()).as("owned fixture %s", command[0]).isZero();
    }

    void inventory(java.nio.file.Path evidence, String phase, String url, String database, UUID userId, UUID eventId) throws Exception {
        Fixture.dataSourceIdentityGuard();
        var ds = new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword());
        var owned = new JdbcTemplate(ds);
        assertThat(owned.queryForObject("select current_database()", String.class)).isEqualTo(database);
        owned.execute("GRANT SELECT ON ALL TABLES IN SCHEMA public TO recovery_reader");
        var queries = json.readValue(java.nio.file.Files.readString(evidence.resolve("inventory-queries.json")),
                new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
        assertThat(queries).hasSize(4);
        var rows = new ArrayList<JsonNode>();
        try (var connection = ds.getConnection()) {
            connection.setAutoCommit(false); connection.setReadOnly(true);
            connection.setTransactionIsolation(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
            try (var statement = connection.createStatement()) {
                statement.execute("SET LOCAL ROLE recovery_reader"); statement.execute("SET LOCAL search_path=pg_catalog");
                statement.execute("SET LOCAL statement_timeout='5s'"); statement.execute("SET LOCAL lock_timeout='1s'");
            }
            for (int i = 0; i < queries.size(); i++) {
                try (var statement = connection.prepareStatement(queries.get(i).replace("$1", "?"))) {
                    if (i == 1 || i == 2) statement.setString(1, json.writeValueAsString(List.of(i == 1 ? userId : eventId)));
                    try (var rs = statement.executeQuery()) {
                        assertThat(rs.next()).isTrue(); rows.add(json.readTree(rs.getString(1))); assertThat(rs.next()).isFalse();
                    }
                }
            }
            connection.rollback();
        }
        writeEvidence(evidence.resolve(phase + "-inventory.json"), Map.of("queries", queries, "rows", rows,
                "database", database, "containerId", POSTGRES.getContainerId()));
    }

    void writeEvidence(java.nio.file.Path path, Object value) throws Exception {
        java.nio.file.Files.writeString(path, json.writerWithDefaultPrettyPrinter().writeValueAsString(value),
                java.nio.file.StandardOpenOption.CREATE_NEW);
    }

    @Test void authenticatedPlanCreatesExactDurableNotificationThroughActualTcpAndBroker() throws Exception {
        assertThat(RABBIT.getContainerInfo().getConfig().getLabels()).containsEntry("soundconnect.fixture", OWNER);
        assertThat(REDIS.getContainerInfo().getConfig().getLabels()).containsEntry("soundconnect.fixture", OWNER);
        doAnswer(i -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) { consumerCommits.add(status); }
            });
            return i.callRealMethod();
        }).when(listener).handle(any());
        var actors = createActors();
        assertThat(actors).isNotNull();
        LocalDate day = LocalDate.now(ZoneId.of("Europe/Istanbul")).plusDays(2);
        var definition = new EventPlanDefinition(actors.venue, day, day, List.of(day.getDayOfWeek().getValue()), List.of(),
                new EventPlanTemplate("BIL004 normal producer", null, LocalTime.of(20, 0), LocalTime.of(22, 0), null, actors.profile, null, null));
        String body = json.writeValueAsString(new EventPlanCreateRequest(UUID.randomUUID(), definition));
        String path = "/api/v1/venue-owner/event-plans";
        request("POST", path, null, body, 401);
        request("POST", path, actors.musicianToken, body, 403);
        request("POST", path, actors.strangerToken, body, 404);
        assertThat(jdbc.queryForObject("select count(*) from tbl_event_performer_notification_outbox", Integer.class)).isZero();
        JsonNode created = request("POST", path, actors.ownerToken, body, 200);
        UUID planId = UUID.fromString(created.at("/data/id").asText());
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(jdbc.queryForObject("select count(*) from tbl_event_performer_notification_outbox where status='PUBLISHED'", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from tbl_notification", Integer.class)).isEqualTo(1);
            assertThat(consumerCommits).containsExactly(TransactionSynchronization.STATUS_COMMITTED);
        });
        var outbox = jdbc.queryForMap("select * from tbl_event_performer_notification_outbox");
        UUID eventId = (UUID) outbox.get("event_id");
        UUID notificationId = jdbc.queryForObject("select id from tbl_notification where source_event_id=? and recipient_id=?", UUID.class, eventId, actors.musician);
        assertThat(outbox).containsEntry("attempt_count", 1).containsEntry("recipient_id", actors.musician)
                .containsEntry("lease_owner", null).containsEntry("lease_until", null);
        assertThat(jdbc.queryForObject("select recipient_id from tbl_notification_receipt where source_event_id=?", UUID.class, eventId)).isEqualTo(actors.musician);
        var captured = org.mockito.ArgumentCaptor.forClass(NotificationInboundEvent.class);
        verify(producer).publishConfirmed(captured.capture());
        assertThat(captured.getValue().eventId()).isEqualTo(eventId);
        assertThat(captured.getValue().recipientId()).isEqualTo(actors.musician);
        String inboxPath = "/api/v1/user/notifications";
        JsonNode exact = request("GET", inboxPath + "/" + notificationId, actors.musicianToken, null, 200);
        assertThat(exact.at("/data/id").asText()).isEqualTo(notificationId.toString());
        assertThat(exact.at("/data/payload/planId").asText()).isEqualTo(planId.toString());
        assertThat(exact.at("/data/read").asBoolean()).isFalse();
        JsonNode list = request("GET", inboxPath, actors.musicianToken, null, 200);
        assertThat(list.at("/data/content").size()).isEqualTo(1);
        assertThat(list.at("/data/content/0/id").asText()).isEqualTo(notificationId.toString());
        assertThat(request("GET", inboxPath + "/unread-count", actors.musicianToken, null, 200).at("/data/unread").asInt()).isEqualTo(1);
        request("GET", inboxPath + "/" + notificationId, actors.strangerToken, null, 404);
        assertThat(jdbc.queryForObject("select count(*) from tbl_notification where is_read", Integer.class)).isZero();
        // Idempotent normal HTTP retry cannot add another outbox occurrence or broker publish.
        assertThat(request("POST", path, actors.ownerToken, body, 200).at("/data/id").asText()).isEqualTo(planId.toString());
        assertThat(jdbc.queryForObject("select count(*) from tbl_event_performer_notification_outbox", Integer.class)).isEqualTo(1);
        verify(producer).publishConfirmed(any());
        System.out.println("BIL004_HTTP_EVIDENCE port=" + port + " plan=" + planId + " event=" + eventId
                + " notification=" + notificationId + " recipient=" + actors.musician + " attempt=1 published=1 receipt=1 inbox=1 unread=1"
                + " pgContainer=" + POSTGRES.getContainerId() + " rabbitContainer=" + RABBIT.getContainerId()
                + " redisContainer=" + REDIS.getContainerId() + " auth=real-signed-fixture-JWT consumerCommit=COMMITTED");
    }

    Actors createActors() {
        return new TransactionTemplate(transactions).execute(status -> {
            User owner = user("ROLE_VENUE"), musician = user("ROLE_MUSICIAN"), stranger = user("ROLE_VENUE");
            City city = persist(City.builder().name("BIL004 City").build());
            District district = persist(District.builder().name("BIL004 District").city(city).build());
            Neighborhood neighborhood = persist(Neighborhood.builder().name("BIL004 Area").district(district).build());
            Venue venue = persist(Venue.builder().name("BIL004 isolated venue").owner(owner).status(VenueStatus.APPROVED)
                    .address("Fixture only").city(city).district(district).neighborhood(neighborhood).build());
            MusicianProfile profile = persist(MusicianProfile.builder().user(musician).stageName("BIL004 musician").build());
            return new Actors(owner.getId(), musician.getId(), stranger.getId(), venue.getId(), profile.getId(),
                    tokens.generateToken(UserDetailsImpl.fromUser(owner)), tokens.generateToken(UserDetailsImpl.fromUser(musician)),
                    tokens.generateToken(UserDetailsImpl.fromUser(stranger)));
        });
    }

    JsonNode request(String method, String path, String token, String body, int expected) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        System.out.println("BIL004_HTTP method=" + method + " path=" + path + " expected=" + expected + " observed=" + response.statusCode());
        assertThat(response.statusCode()).as("%s %s response: %s", method, path, response.body()).isEqualTo(expected);
        return json.readTree(response.body());
    }
    User user(String roleName) {
        Role role = em.createQuery("select r from Role r where r.name=:name", Role.class).setParameter("name", roleName)
                .getResultStream().findFirst().orElseGet(() -> persist(Role.builder().name(roleName).build()));
        return persist(User.builder().username("bil" + UUID.randomUUID().toString().replace("-", "").substring(0, 16))
                .email(UUID.randomUUID() + "@test.invalid").password("fixture-only").roles(Set.of(role))
                .status(UserStatus.ACTIVE).emailVerified(true).build());
    }
    <T> T persist(T entity) { em.persist(entity); return entity; }
    // Never log this record: its JWT fields are ephemeral credentials.
    record Actors(UUID owner, UUID musician, UUID stranger, UUID venue, UUID profile,
                  String ownerToken, String musicianToken, String strangerToken) { }
}
