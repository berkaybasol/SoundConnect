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
@SpringBootTest(classes = NotificationDlqAuthenticatedHttpPostgresRabbitIT.Fixture.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.location=optional:classpath:/bil005-http-isolated-test.yml", "spring.config.import=",
        "server.address=127.0.0.1", "spring.main.web-application-type=servlet",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.open-in-view=false",
        "spring.rabbitmq.publisher-confirm-type=correlated", "spring.rabbitmq.publisher-returns=true",
        "spring.rabbitmq.template.mandatory=true", "spring.rabbitmq.connection-timeout=2s",
        "app.messaging.notification.exchange=bil005.http.exchange", "app.messaging.notification.queue=bil005.http.queue",
        "app.messaging.notification.routingKey=notification.#", "app.messaging.notification.dlxExchange=bil005.http.dlx",
        "app.messaging.notification.dlq=bil005.http.dlq", "app.messaging.notification.publisher-confirm-timeout=2s",
        "app.messaging.notification.dlq-ops.replay-enabled=true", "app.messaging.notification.dlq-ops.interval-ms=1000",
        "app.jwt.expiration=3600000", "app.jwt.issuer=bil005-isolated",
        "app.share-base-url=https://bil005.invalid", "app.notification.push.enabled=false",
        "logging.level.org.hibernate.SQL=OFF", "logging.level.org.hibernate.orm.jdbc.bind=OFF"
})
@DirtiesContext
class NotificationDlqAuthenticatedHttpPostgresRabbitIT {
    static final String OWNER = "bil005-authenticated-http";
    static final String KEY = UUID.randomUUID().toString() + UUID.randomUUID();
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("bil005_http").withLabel("soundconnect.fixture", OWNER).withReuse(false);
    @Container static final RabbitMQContainer RABBIT = new RabbitMQContainer(org.testcontainers.utility.DockerImageName.parse("soundconnect-rabbitmq:3.13.7").asCompatibleSubstituteFor("rabbitmq"))
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

    @org.springframework.scheduling.annotation.EnableScheduling
    @ComponentScan("com.berkayb.soundconnect.modules.notification.dlq")
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
        @Bean DataSource dataSource() throws Exception {
            assertThat(POSTGRES.getContainerInfo().getConfig().getLabels()).containsEntry("soundconnect.fixture", OWNER);
            var ds = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
            try (var c = ds.getConnection()) {
                assertThat(c.getMetaData().getURL()).isEqualTo(POSTGRES.getJdbcUrl());
                assertThat(c.getCatalog()).isEqualTo("bil005_http");
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
    @Autowired RabbitTemplate rabbit;
    @MockitoSpyBean NotificationProducer producer;
    @MockitoSpyBean NotificationEventListener listener;
    @MockitoBean MediaAssetService media;
    @MockitoBean NotificationWebSocketService websocket;
    @MockitoBean MailProducer mail;
    final List<Integer> consumerCommits = new CopyOnWriteArrayList<>();
    final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Test void authenticatedPlanCreatesExactDurableNotificationThroughActualTcpAndBroker() throws Exception {
        assertThat(RABBIT.getContainerInfo().getConfig().getLabels()).containsEntry("soundconnect.fixture", OWNER);
        assertThat(REDIS.getContainerInfo().getConfig().getLabels()).containsEntry("soundconnect.fixture", OWNER);
        java.util.concurrent.atomic.AtomicBoolean fault = new java.util.concurrent.atomic.AtomicBoolean(true);
        doAnswer(i -> {
            if (fault.get()) throw new org.springframework.amqp.AmqpRejectAndDontRequeueException("Isolated DLQ fault");
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) { consumerCommits.add(status); }
            });
            return i.callRealMethod();
        }).when(listener).handle(any());
        var actors = new TransactionTemplate(transactions).execute(status -> {
            User owner = user("ROLE_VENUE"), musician = user("ROLE_MUSICIAN"), stranger = user("ROLE_VENUE");
            City city = persist(City.builder().name("BIL005 City").build());
            District district = persist(District.builder().name("BIL005 District").city(city).build());
            Neighborhood neighborhood = persist(Neighborhood.builder().name("BIL005 Area").district(district).build());
            Venue venue = persist(Venue.builder().name("BIL005 isolated venue").owner(owner).status(VenueStatus.APPROVED)
                    .address("Fixture only").city(city).district(district).neighborhood(neighborhood).build());
            MusicianProfile profile = persist(MusicianProfile.builder().user(musician).stageName("BIL005 musician").build());
            return new Actors(owner.getId(), musician.getId(), stranger.getId(), venue.getId(), profile.getId(),
                    tokens.generateToken(UserDetailsImpl.fromUser(owner)), tokens.generateToken(UserDetailsImpl.fromUser(musician)),
                    tokens.generateToken(UserDetailsImpl.fromUser(stranger)));
        });
        assertThat(actors).isNotNull();
        LocalDate day = LocalDate.now(ZoneId.of("Europe/Istanbul")).plusDays(2);
        var definition = new EventPlanDefinition(actors.venue, day, day, List.of(day.getDayOfWeek().getValue()), List.of(),
                new EventPlanTemplate("BIL005 normal producer", null, LocalTime.of(20, 0), LocalTime.of(22, 0), null, actors.profile, null, null));
        String body = json.writeValueAsString(new EventPlanCreateRequest(UUID.randomUUID(), definition));
        String path = "/api/v1/venue-owner/event-plans";
        request("POST", path, null, body, 401);
        request("POST", path, actors.musicianToken, body, 403);
        request("POST", path, actors.strangerToken, body, 404);
        assertThat(jdbc.queryForObject("select count(*) from tbl_event_performer_notification_outbox", Integer.class)).isZero();
        JsonNode created = request("POST", path, actors.ownerToken, body, 200);
        UUID planId = UUID.fromString(created.at("/data/id").asText());

        String ops = "/api/v1/admin/notifications/dlq";
        String[] adminTokens = new TransactionTemplate(transactions).execute(status -> {
            User admin = user("ROLE_ADMIN"), ownerOps = user("ROLE_OWNER"), mixed = user("ROLE_ADMIN");
            Role listenerRole = em.createQuery("select r from Role r where r.name=:n", Role.class).setParameter("n", "ROLE_LISTENER")
                    .getResultStream().findFirst().orElseGet(() -> persist(Role.builder().name("ROLE_LISTENER").build()));
            Set<Role> roles = new HashSet<>(mixed.getRoles()); roles.add(listenerRole); mixed.setRoles(roles); em.flush();
            persist(com.berkayb.soundconnect.modules.profile.ListenerProfile.entity.ListenerProfile.builder()
                    .user(mixed).visibilityChoiceCompleted(true).build()); em.flush();
            return new String[]{tokens.generateToken(UserDetailsImpl.fromUser(admin)), tokens.generateToken(UserDetailsImpl.fromUser(ownerOps)), tokens.generateToken(UserDetailsImpl.fromUser(mixed))};
        });
        request("GET", ops + "/summary", null, null, 401);
        request("GET", ops + "/summary", adminTokens[0], null, 200); // Same baseline assertion: authorized endpoint absent -> 404.
        request("POST", ops + "/inspect", actors.musicianToken, null, 403);
        request("POST", ops + "/inspect", adminTokens[2], null, 403);
        request("GET", ops + "/summary", adminTokens[1], null, 200);
        await().atMost(Duration.ofSeconds(25)).untilAsserted(() -> {
            assertThat(jdbc.queryForObject("select count(*) from tbl_event_performer_notification_outbox where status='PUBLISHED'", Integer.class)).isEqualTo(1);
            assertThat(ready("bil005.http.dlq")).isEqualTo(1);
        });
        assertThat(jdbc.queryForObject("select count(*) from tbl_notification_receipt", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from tbl_notification", Integer.class)).isZero();
        var outbox = jdbc.queryForMap("select * from tbl_event_performer_notification_outbox");
        UUID eventId = (UUID) outbox.get("event_id");
        // Capture the original broker bytes for subsequent duplicate tests; requeue on the same channel.
        org.springframework.amqp.core.Message original = rabbit.execute(ch -> {
            var d = ch.basicGet("bil005.http.dlq", false);
            ch.basicNack(d.getEnvelope().getDeliveryTag(), false, true);
            var props = new org.springframework.amqp.rabbit.support.DefaultMessagePropertiesConverter().toMessageProperties(d.getProps(), d.getEnvelope(), "UTF-8");
            return new org.springframework.amqp.core.Message(d.getBody(), props);
        });
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(ready("bil005.http.dlq")).isEqualTo(1));
        for (int i = 0; i < 3; i++) {
            var summary = request("GET", ops + "/summary", adminTokens[0], null, 200).path("data");
            assertThat(summary.path("oldest").path("seconds").isNull()).isTrue();
            assertThat(summary.path("totalMessages").isNull()).isTrue();
            assertThat(ready("bil005.http.dlq")).isEqualTo(1);
        }
        // Explicit invalid negative fixture is a sibling; never counted as the normal producer proof.
        rabbit.send("", "bil005.http.dlq", new org.springframework.amqp.core.Message("poison-private".getBytes(), new org.springframework.amqp.core.MessageProperties()));
        JsonNode inspection = request("POST", ops + "/inspect", adminTokens[0], null, 200).path("data");
        assertThat(inspection.toString()).doesNotContain("poison-private", "normal producer", actors.musician.toString(), "Bearer");
        JsonNode selected = java.util.stream.StreamSupport.stream(inspection.path("messages").spliterator(), false)
                .filter(n -> n.path("eventId").asText().equals(eventId.toString())).findFirst().orElseThrow();
        assertThat(selected.path("replayable").asBoolean()).isTrue();
        assertThat(selected.path("age").path("source").asText()).isEqualTo("MATCHING_X_DEATH_FIRST");
        String selection = json.writeValueAsString(Map.of("eventId", eventId, "fingerprint", selected.path("fingerprint").asText()));
        assertThat(ready("bil005.http.dlq")).isEqualTo(2);
        request("POST", ops + "/replay", null, selection, 401);
        request("POST", ops + "/replay", actors.musicianToken, selection, 403);
        request("POST", ops + "/replay", adminTokens[2], selection, 403);
        request("POST", ops + "/replay", adminTokens[0], selection.substring(0, selection.length()-1) + ",\"queue\":\"other\"}", 400);
        request("POST", ops + "/inspect", adminTokens[0], "{\"queue\":\"other\"}", 400);
        request("POST", ops + "/replay", adminTokens[0], selection + "{}", 400);
        request("POST", ops + "/replay", adminTokens[0], "x".repeat(2049), 400);
        assertThat(ready("bil005.http.dlq")).isEqualTo(2);
        String stale = json.writeValueAsString(Map.of("eventId", eventId, "fingerprint", "0".repeat(64)));
        assertThat(request("POST", ops + "/replay", adminTokens[0], stale, 200).at("/data/outcome").asText()).isEqualTo("NOT_FOUND_IN_WINDOW");
        fault.set(false);
        var replay = request("POST", ops + "/replay", adminTokens[0], selection, 200).path("data");
        assertThat(replay.path("brokerAcceptance").asText()).isEqualTo("CONFIRMED_ROUTED");
        assertThat(replay.path("sourceAck").asText()).isEqualTo("ACK_PROCESSED");
        assertThat(replay.path("consumerOutcome").asText()).isEqualTo("NOT_OBSERVED");
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(jdbc.queryForObject("select count(*) from tbl_notification", Integer.class)).isEqualTo(1);
            assertThat(consumerCommits).contains(TransactionSynchronization.STATUS_COMMITTED);
        });
        UUID notificationId = jdbc.queryForObject("select id from tbl_notification where source_event_id=? and recipient_id=?", UUID.class, eventId, actors.musician);
        String inbox = "/api/v1/user/notifications";
        assertThat(request("GET", inbox + "/" + notificationId, actors.musicianToken, null, 200).at("/data/payload/planId").asText()).isEqualTo(planId.toString());
        assertThat(request("GET", inbox, actors.musicianToken, null, 200).at("/data/content").size()).isEqualTo(1);
        assertThat(request("GET", inbox + "/unread-count", actors.musicianToken, null, 200).at("/data/unread").asInt()).isEqualTo(1);
        assertThat(ready("bil005.http.dlq")).isEqualTo(1);
        request("POST", inbox + "/" + notificationId + "/read", actors.musicianToken, null, 200);
        int commits = consumerCommits.size();
        rabbit.send("", "bil005.http.dlq", original);
        request("POST", ops + "/replay", adminTokens[0], selection, 200);
        final int beforeReadReplay = commits;
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(consumerCommits.size()).isGreaterThan(beforeReadReplay));
        assertThat(jdbc.queryForObject("select count(*) from tbl_notification where is_read", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from tbl_notification_receipt", Integer.class)).isEqualTo(1);
        request("DELETE", inbox + "/" + notificationId, actors.musicianToken, null, 200);
        commits = consumerCommits.size();
        rabbit.send("", "bil005.http.dlq", original);
        request("POST", ops + "/replay", adminTokens[0], selection, 200);
        final int beforeDeleteReplay = commits;
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(consumerCommits.size()).isGreaterThan(beforeDeleteReplay));
        assertThat(jdbc.queryForObject("select count(*) from tbl_notification", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select recipient_id from tbl_notification_receipt where source_event_id=?", UUID.class, eventId)).isEqualTo(actors.musician);
        // Real current policy suppression and hostile duplicate recipient use normal consumer transactions.
        var captured = org.mockito.ArgumentCaptor.forClass(NotificationInboundEvent.class);
        verify(producer).publishConfirmed(captured.capture());
        var source = captured.getValue();
        producer.publishConfirmed(new NotificationInboundEvent(source.eventId(), actors.stranger, source.type(), source.title(), source.message(), source.payload(), false, source.occurredAt()));
        UUID suppressed = UUID.randomUUID();
        producer.publishConfirmed(new NotificationInboundEvent(suppressed, actors.musician,
                com.berkayb.soundconnect.modules.notification.enums.NotificationType.DM_NEW_MESSAGE, "suppressed", "", Map.of(), false, Instant.now()));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(jdbc.queryForObject("select count(*) from tbl_notification_receipt", Integer.class)).isEqualTo(2));
        assertThat(jdbc.queryForObject("select count(*) from tbl_notification", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select recipient_id from tbl_notification_receipt where source_event_id=?", UUID.class, eventId)).isEqualTo(actors.musician);
        // A healthy sibling progresses through the same normal HTTP producer after fault removal.
        var nextDefinition = new EventPlanDefinition(actors.venue, day.plusDays(1), day.plusDays(1), List.of(day.plusDays(1).getDayOfWeek().getValue()), List.of(), definition.template());
        request("POST", path, actors.ownerToken, json.writeValueAsString(new EventPlanCreateRequest(UUID.randomUUID(), nextDefinition)), 200);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(jdbc.queryForObject("select count(*) from tbl_notification", Integer.class)).isEqualTo(1));
        assertThat(ready("bil005.http.dlq")).isEqualTo(1);
        System.out.println("BIL005_HTTP_EVIDENCE normalProducer=HTTP_EVENT_PLAN outbox=PUBLISHED beforeReceipt=0 beforeInbox=0 replay=CONFIRMED_ROUTED sourceAck=ACK_PROCESSED consumer=COMMITTED readPreserved=true deletePreserved=true recipientPreserved=true suppression=true poisonSibling=1 healthySibling=1 auth=normalJWT_DB_roles pushEnabled=false pg=" + POSTGRES.getDockerImageName() + " rabbit=" + RABBIT.getDockerImageName() + " redis=" + REDIS.getDockerImageName());
    }
    int ready(String queue) { return rabbit.execute(ch -> ch.queueDeclarePassive(queue).getMessageCount()); }

    JsonNode request(String method, String path, String token, String body, int expected) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        System.out.println("BIL005_HTTP method=" + method + " path=" + path + " expected=" + expected + " observed=" + response.statusCode());
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
