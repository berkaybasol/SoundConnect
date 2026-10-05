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
@DirtiesContext
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
        var actors = new TransactionTemplate(transactions).execute(status -> {
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
