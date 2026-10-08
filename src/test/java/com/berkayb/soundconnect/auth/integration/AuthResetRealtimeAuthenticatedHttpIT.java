package com.berkayb.soundconnect.auth.integration;

import com.berkayb.soundconnect.auth.controller.AuthControllerImpl;
import com.berkayb.soundconnect.auth.otp.service.*;
import com.berkayb.soundconnect.auth.passwordreset.service.*;
import com.berkayb.soundconnect.auth.ratelimit.*;
import com.berkayb.soundconnect.auth.security.*;
import com.berkayb.soundconnect.auth.service.*;
import com.berkayb.soundconnect.modules.application.studioapplication.service.StudioApplicationService;
import com.berkayb.soundconnect.modules.application.venueapplication.service.*;
import com.berkayb.soundconnect.modules.admin.health.AuthMonitorFixtureConfiguration;
import com.berkayb.soundconnect.modules.marketplace.media.MarketplaceMediaLifecycle;
import com.berkayb.soundconnect.modules.notification.websocket.*;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.support.*;
import com.berkayb.soundconnect.modules.profile.shared.factory.ProfileFactory;
import com.berkayb.soundconnect.modules.profile.shared.resolver.service.PublicProfileResolverService;
import com.berkayb.soundconnect.modules.profile.shared.type.PersonalProfileTypePolicy;
import com.berkayb.soundconnect.modules.pulse.redis.PulseRedisService;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.role.entity.Permission;
import com.berkayb.soundconnect.modules.user.controller.user.UserAccountController;
import com.berkayb.soundconnect.modules.user.deletion.ListenerAccountDeletionService;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.modules.user.mapper.UserMapper;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.modules.user.service.UserServiceImpl;
import com.berkayb.soundconnect.modules.user.support.*;
import com.berkayb.soundconnect.shared.config.*;
import com.berkayb.soundconnect.shared.exception.*;
import com.berkayb.soundconnect.shared.mail.helper.*;
import com.berkayb.soundconnect.shared.mail.producer.MailProducerImpl;
import com.berkayb.soundconnect.shared.realtime.*;
import com.berkayb.soundconnect.shared.security.*;
import com.berkayb.soundconnect.shared.util.JwtUtil;
import com.fasterxml.jackson.databind.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.testcontainers.containers.*;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual loopback TCP/HTTP, BCrypt login, Redis OTP/guards, PostgreSQL commits and
 * Rabbit STOMP relay. Only unrelated registration/profile/deletion services are
 * doubles. Reset mail is really queued in owned Rabbit, with no mail consumer or
 * external mail provider. No application env/config or shared dependency is read. */
@Testcontainers
@SpringBootTest(classes = AuthResetRealtimeAuthenticatedHttpIT.Fixture.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.location=optional:classpath:/ready-for-prod-01-auth-isolated.yml", "spring.config.import=",
        "server.address=127.0.0.1", "spring.main.web-application-type=servlet",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.open-in-view=false",
        "spring.data.redis.timeout=350ms", "spring.data.redis.connect-timeout=1s",
        "spring.rabbitmq.publisher-confirm-type=correlated", "spring.rabbitmq.publisher-returns=true",
        "spring.rabbitmq.template.mandatory=true", "spring.rabbitmq.connection-timeout=2s",
        "mail.queueName=ready01.fixture.mail", "mail.exchange=ready01.fixture.mail.exchange",
        "mail.routingKey=ready01.fixture.mail.send", "mail.dlq=ready01.fixture.mail.dlq",
        "mail.dlx=ready01.fixture.mail.dlx", "mail.delayed.exchange=ready01.fixture.mail.delayed",
        "mail.producer.confirmTimeoutSec=2", "spring.rabbitmq.listener.simple.auto-startup=false",
        "app.jwt.expiration=3600000", "app.jwt.issuer=ready01-auth-http",
        "app.websocket.broker-relay.enabled=true", "app.security.auth-rate-limit.enabled=true",
        "app.security.auth-rate-limit.key-prefix=ready01:auth-fixture", "otp.resend.cooldown.seconds=1",
        "logging.level.org.hibernate.SQL=OFF", "logging.level.org.hibernate.orm.jdbc.bind=OFF"
})
@DirtiesContext
@Timeout(90)
class AuthResetRealtimeAuthenticatedHttpIT {
    static final String OWNER = "ready-for-prod-01-auth-http-realtime";
    static final String KEY = UUID.randomUUID().toString() + UUID.randomUUID();
    static final String OLD_PASSWORD = "Fixture-before-2026";
    static final String NEW_PASSWORD = "Fixture-after-2026";
    static final String USERNAME_PATH = "/api/v1/users/me/username";
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("ready01_auth_http").withLabel("soundconnect.fixture", OWNER).withReuse(false);
    @Container static final RabbitMQContainer RABBIT = new RabbitMQContainer(
            DockerImageName.parse("soundconnect-rabbitmq:3.13.7").asCompatibleSubstituteFor("rabbitmq"))
            .withExposedPorts(5672, 15672, 61613).withLabel("soundconnect.fixture", OWNER).withReuse(false);
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.2.5-alpine")
            .withExposedPorts(6379).withLabel("soundconnect.fixture", OWNER).withReuse(false);

    @DynamicPropertySource static void services(DynamicPropertyRegistry r) {
        r.add("ready01.monitor.hold.enabled", () -> "true".equals(System.getenv("READY01_MONITOR_HOLD")));
        r.add("app.jwt.secret", () -> KEY);
        r.add("spring.rabbitmq.host", RABBIT::getHost); r.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        r.add("spring.rabbitmq.username", RABBIT::getAdminUsername); r.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
        r.add("spring.data.redis.host", REDIS::getHost); r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        r.add("app.websocket.broker-relay.host", RABBIT::getHost);
        r.add("app.websocket.broker-relay.port", () -> RABBIT.getMappedPort(61613));
        r.add("app.websocket.broker-relay.login", RABBIT::getAdminUsername);
        r.add("app.websocket.broker-relay.passcode", RABBIT::getAdminPassword);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EntityScan("com.berkayb.soundconnect")
    @EnableJpaRepositories("com.berkayb.soundconnect")
    @EnableTransactionManagement(proxyTargetClass = true)
    @Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtTokenProvider.class,
            CustomUserDetailsService.class, JwtUtil.class, ListenerProfileChoiceGate.class,
            ListenerProfileChoiceStatusReader.class, VenueApplicationSessionAccess.class,
            AuthRateLimitConfiguration.class, AuthRateLimiter.class, AuthAccountRateLimitGuard.class,
            SecurityErrorResponseWriter.class, RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class,
            GlobalExceptionHandler.class, AuthControllerImpl.class, AuthService.class, OtpService.class,
            PasswordResetService.class, PasswordResetMailService.class, RedisConfig.class,
            MailProducerImpl.class, MailJobHelper.class, MailContentBuilder.class, MailQueueConfig.class,
            UserAccountController.class, UserServiceImpl.class, UserEntityFinder.class, UsernameChangeTimeProvider.class,
            WebSocketConfig.class, WebSocketSecurityInterceptor.class, WebSocketSessionRegistry.class,
            WebSocketSubscriptionAuthorizer.class, NotificationWebSocketServiceImpl.class, JpaAuditingConfig.class,
            AuthMonitorFixtureConfiguration.class})
    static class Fixture {
        @Bean DataSource dataSource() throws Exception {
            assertThat(POSTGRES.getContainerInfo().getConfig().getLabels()).containsEntry("soundconnect.fixture", OWNER);
            var ds = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
            try (var c = ds.getConnection()) {
                assertThat(c.getMetaData().getURL()).isEqualTo(POSTGRES.getJdbcUrl());
                assertThat(c.getCatalog()).isEqualTo("ready01_auth_http");
            }
            return ds;
        }
        @Bean UserMapper mapper() { return org.mapstruct.factory.Mappers.getMapper(UserMapper.class); }
        @Bean Jackson2JsonMessageConverter converter(ObjectMapper mapper) { return new Jackson2JsonMessageConverter(mapper); }
    }

    @LocalServerPort int port;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager transactions;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper json;
    @Autowired JwtTokenProvider tokens;
    @Autowired RabbitTemplate rabbit;
    @Autowired NotificationWebSocketService notifications;
    @Autowired ServletWebServerApplicationContext serverContext;
    @MockitoSpyBean WebSocketSecurityInterceptor websocketSecurity;
    @MockitoSpyBean AuthRateLimiter limiter;
    // These dependencies belong to untested endpoints; none participates in login/reset/username or personal topic auth.
    @MockitoBean OtpMailService otpMail;
    @MockitoBean ProfileFactory profileFactory;
    @MockitoBean VenueApplicationService venueApplication;
    @MockitoBean StudioApplicationService studioApplication;
    @MockitoBean PublicProfileResolverService profiles;
    @MockitoBean PersonalProfileTypePolicy profilePolicy;
    @MockitoBean ListenerProfileProvisioner listenerProvisioner;
    @MockitoBean ListenerAccountDeletionService deletion;
    @MockitoBean MarketplaceMediaLifecycle marketplace;
    @MockitoBean PulseRedisService pulse;
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static AuthResetRealtimeAuthenticatedHttpIT monitorInstance;

    @AfterEach void retainOptedInMonitorInstance() {
        if ("true".equals(System.getenv("READY01_MONITOR_HOLD"))) monitorInstance = this;
    }

    @AfterAll @Timeout(690)
    static void holdMonitorWhenOptedIn() throws Exception {
        try {
            if (monitorInstance != null) monitorInstance.holdOwnedApiForMonitorAcceptance();
        } finally { monitorInstance = null; }
    }

    /** Root-owned external monitor acceptance hook. Closed unless explicitly enabled.
     * The command file accepts only stop/start/finish and controls this fixture's HTTP
     * connector, never a process, host or shared dependency. The token is never logged. */
    private void holdOwnedApiForMonitorAcceptance() throws Exception {
        if (!"true".equals(System.getenv("READY01_MONITOR_HOLD"))) return;
        Path directory = Path.of("tmp", "ready-for-prod-01", "private").toAbsolutePath().normalize();
        Files.createDirectories(directory);
        if (Files.isSymbolicLink(directory)) throw new IllegalStateException("Private fixture directory must not be a link");
        Path descriptor = directory.resolve("live-http.json");
        Path pending = directory.resolve("live-http.pending");
        Path command = directory.resolve("live-http.command");
        Path state = directory.resolve("live-http-state.json");
        if (Files.exists(descriptor) || Files.exists(pending) || Files.exists(command))
            throw new IllegalStateException("Previous private monitor fixture files must be archived before starting");

        String adminToken = new TransactionTemplate(transactions).execute(status -> {
            Permission permission = Permission.builder().name("ADMIN_PANEL_ACCESS").build(); em.persist(permission);
            Role role = Role.builder().name("ROLE_ADMIN").permissions(Set.of(permission)).build(); em.persist(role);
            User user = User.builder().username("monitor-" + UUID.randomUUID().toString().substring(0, 8))
                    .email("monitor-" + UUID.randomUUID() + "@example.invalid").password(encoder.encode(OLD_PASSWORD))
                    .status(UserStatus.ACTIVE).emailVerified(true).roles(Set.of(role)).build();
            em.persist(user); em.flush(); return tokens.generateToken(UserDetailsImpl.fromUser(user));
        });
        await().atMost(Duration.ofSeconds(45)).untilAsserted(() -> {
            JsonNode data = json.readTree(request("GET", "/api/v1/admin/system-health", adminToken, null, 200).body()).path("data");
            assertThat(data.path("status").asText()).isEqualTo("UP");
            Set<String> observed = new HashSet<>();
            data.path("components").forEach(component -> observed.add(component.path("id").asText()));
            assertThat(observed).isEqualTo(AuthMonitorFixtureConfiguration.PROBE_IDS);
        });
        var connector = ((TomcatWebServer) serverContext.getWebServer()).getTomcat().getConnector();
        Instant deadline = Instant.now().plusSeconds(600);
        Files.writeString(pending, json.writeValueAsString(Map.of("baseUrl", "http://127.0.0.1:" + port,
                "healthUrl", "http://127.0.0.1:" + port + "/api/v1/admin/system-health", "port", port,
                "token", adminToken, "deadlineUtc", deadline.toString(), "probeIds", AuthMonitorFixtureConfiguration.PROBE_IDS)),
                StandardOpenOption.CREATE_NEW);
        Files.move(pending, descriptor, StandardCopyOption.ATOMIC_MOVE);
        boolean stopped = false;
        String previous = "";
        try {
            monitorStage(state, "READY");
            while (Instant.now().isBefore(deadline)) {
                if (Files.exists(command)) {
                    if (Files.size(command) > 32) throw new IllegalStateException("Monitor command too long");
                    String next = Files.readString(command).trim();
                    if (!next.equals(previous)) {
                        previous = next;
                        if ("finish".equals(next)) { monitorStage(state, "FINISHED"); return; }
                        if ("stop".equals(next) && !stopped) {
                            connector.stop(); connector.setPort(port); stopped = true; monitorStage(state, "STOPPED");
                        } else if ("start".equals(next) && stopped) {
                            connector.start(); stopped = false;
                            request("GET", "/api/v1/admin/system-health", adminToken, null, 200);
                            monitorStage(state, "STARTED");
                        } else if (!Set.of("start", "stop").contains(next)) {
                            throw new IllegalStateException("Unknown monitor command");
                        }
                    }
                }
                Thread.sleep(250);
            }
            monitorStage(state, "TIMED_OUT");
        } finally {
            if (stopped) connector.start();
            // Destroy only this hook's ephemeral credential, keeping credential-free evidence/state.
            Files.deleteIfExists(descriptor);
        }
    }

    private void monitorStage(Path file, String stage) throws Exception {
        Path pending = file.resolveSibling(file.getFileName() + ".pending");
        Files.writeString(pending, json.writeValueAsString(Map.of("stage", stage, "at", Instant.now().toString())));
        Files.move(pending, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        System.out.println("READY01_MONITOR_" + stage);
    }

    @Test void passwordResetThroughActualHttpRevokesBothDevicesAndAlreadyOpenRabbitSubscriptions() throws Exception {
        Actor actor = actor();
        String deviceOne = login(actor, OLD_PASSWORD), deviceTwo = login(actor, OLD_PASSWORD);
        request("PATCH", USERNAME_PATH, deviceOne, Map.of("username", actor.username), 200);
        AtomicInteger droppedFrames = new AtomicInteger();
        doAnswer(invocation -> {
            Message<?> input = invocation.getArgument(0);
            Object result = invocation.callRealMethod();
            if (SimpMessageHeaderAccessor.getMessageType(input.getHeaders()) == SimpMessageType.MESSAGE && result == null)
                droppedFrames.incrementAndGet();
            return result;
        }).when(websocketSecurity).preSend(any(Message.class), any(MessageChannel.class));

        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1); scheduler.initialize();
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setTaskScheduler(scheduler);
        var converter = new MappingJackson2MessageConverter(); converter.setObjectMapper(json);
        client.setMessageConverter(converter);
        List<StompSession> sessions = new ArrayList<>();
        try {
            Socket first = connect(client, deviceOne, actor.id); sessions.add(first.session);
            Socket second = connect(client, deviceTwo, actor.id); sessions.add(second.session);
            notifications.sendUnreadBadgeToUser(actor.id, 17);
            assertThat(first.messages.poll(10, TimeUnit.SECONDS)).isEqualTo(17L);
            assertThat(second.messages.poll(10, TimeUnit.SECONDS)).isEqualTo(17L);

            // Actual request -> real Redis OTP -> confirmed owned Rabbit message. No external mail consumer exists.
            request("POST", "/api/v1/auth/forgot-password", null, Map.of("identifier", actor.username), 200);
            var mail = rabbit.receive("ready01.fixture.mail", 5000);
            assertThat(mail).isNotNull();
            JsonNode queued = json.readTree(mail.getBody());
            assertThat(queued.path("to").asText()).isEqualTo(actor.email);
            var codeMatch = Pattern.compile("\\b\\d{6}\\b").matcher(queued.path("textBody").asText());
            assertThat(codeMatch.find()).isTrue();
            String code = codeMatch.group();
            Map<String, Object> reset = Map.of("identifier", actor.username, "code", code,
                    "password", NEW_PASSWORD, "rePassword", NEW_PASSWORD);
            request("POST", "/api/v1/auth/reset-password", null, reset, 200);
            User changed = users.findById(actor.id).orElseThrow();
            assertThat(changed.getSessionVersion()).isEqualTo(1L);
            assertThat(encoder.matches(NEW_PASSWORD, changed.getPassword())).isTrue();
            assertThat(encoder.matches(OLD_PASSWORD, changed.getPassword())).isFalse();

            for (String old : List.of(deviceOne, deviceTwo, deviceOne))
                request("PATCH", USERNAME_PATH, old, Map.of("username", "must-not-be-written"), 401);
            assertThat(users.findById(actor.id).orElseThrow().getUsername()).isEqualTo(actor.username);
            request("POST", "/api/v1/auth/reset-password", null, reset, 400); // Consumed OTP cannot repeat.
            assertThat(users.findById(actor.id).orElseThrow().getSessionVersion()).isEqualTo(1L);
            request("POST", "/api/v1/auth/login", null, Map.of("username", actor.username, "password", OLD_PASSWORD), 401);
            String current = login(actor, NEW_PASSWORD);
            assertThat(tokens.getSessionVersionFromToken(current)).isEqualTo(1L);
            Socket fresh = connect(client, current, actor.id); sessions.add(fresh.session);
            notifications.sendUnreadBadgeToUser(actor.id, 29);
            assertThat(fresh.messages.poll(10, TimeUnit.SECONDS)).isEqualTo(29L);
            await().atMost(Duration.ofSeconds(5)).until(() -> droppedFrames.get() >= 2);
            assertThat(first.messages.poll(300, TimeUnit.MILLISECONDS)).isNull();
            assertThat(second.messages.poll(300, TimeUnit.MILLISECONDS)).isNull();
            String renamed = actor.username + "x";
            request("PATCH", USERNAME_PATH, current, Map.of("username", renamed), 200);
            assertThat(users.findById(actor.id).orElseThrow().getUsername()).isEqualTo(renamed);
            System.out.println("READY01_A_HTTP_REALTIME_PASS: login=2 reset=200 version=1 old-token-mutations=401 otp-reuse=400 old-password=401 new-login=200 old-socket-frames-dropped=2 new-socket-delivery=PASS persisted-new-session-mutation=PASS");
        } finally {
            for (StompSession session : sessions) { try { if (session.isConnected()) session.disconnect(); } catch (Exception ignored) { } }
            client.stop(); scheduler.shutdown();
        }
    }

    @Test void realRedisStallReturnsBounded503ForIpAndAccountGuardsAndRecoversOverHttp() throws Exception {
        Actor actor = actor();
        login(actor, OLD_PASSWORD);
        assertOwnedRedis();
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
        long started = System.nanoTime();
        try {
            for (String path : List.of("/api/v1/auth/login", "/api/v1/auth/forgot-password", "/api/v1/auth/reset-password")) {
                var unavailable = request("POST", path, null, Map.of("username", actor.username, "password", OLD_PASSWORD,
                        "identifier", actor.username, "code", "111111", "rePassword", OLD_PASSWORD), 503);
                assertUnavailable(unavailable, actor);
            }
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(8));
        } finally { REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec(); }
        login(actor, OLD_PASSWORD);

        // Fault placement after a real successful IP Redis call, before the controller's real account guard.
        AtomicBoolean armAccountFault = new AtomicBoolean(true);
        doAnswer(invocation -> {
            Object permitted = invocation.callRealMethod();
            if (armAccountFault.compareAndSet(true, false)) {
                assertOwnedRedis(); REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
            }
            return permitted;
        }).when(limiter).check(eq("login"), anyString(), any(AuthRateLimitProperties.Policy.class));
        try {
            assertUnavailable(request("POST", "/api/v1/auth/login", null,
                    Map.of("username", actor.username, "password", OLD_PASSWORD), 503), actor);
        } finally { REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec(); }
        String recovered = login(actor, OLD_PASSWORD);
        request("PATCH", USERNAME_PATH, recovered, Map.of("username", actor.username), 200);
        assertThat(users.findById(actor.id).orElseThrow().getSessionVersion()).isZero();
        System.out.println("READY01_B_HTTP_REDIS_PASS: IP-outage-login-forgot-reset=503 Retry-After=5 bounded=PASS account-outage-after-IP=503 recovery-login=200 persisted-version=0");
    }

    private Socket connect(WebSocketStompClient client, String token, UUID userId) throws Exception {
        BlockingQueue<Long> messages = new LinkedBlockingQueue<>();
        StompHeaders connect = new StompHeaders(); connect.add("Authorization", "Bearer " + token);
        StompSession session = client.connectAsync("ws://127.0.0.1:" + port + "/ws/websocket",
                new WebSocketHttpHeaders(), connect, new StompSessionHandlerAdapter() { }).get(15, TimeUnit.SECONDS);
        session.setAutoReceipt(true);
        CountDownLatch subscribed = new CountDownLatch(1);
        StompSession.Subscription subscription = session.subscribe(WebSocketChannels.notificationsBadge(userId), new StompFrameHandler() {
            @Override public Type getPayloadType(StompHeaders headers) { return Long.class; }
            @Override public void handleFrame(StompHeaders headers, Object payload) { messages.add((Long) payload); }
        });
        subscription.addReceiptTask(subscribed::countDown);
        assertThat(subscribed.await(10, TimeUnit.SECONDS)).isTrue();
        return new Socket(session, messages);
    }

    private Actor actor() {
        return new TransactionTemplate(transactions).execute(status -> {
            Role role = em.createQuery("select r from Role r where r.name='ROLE_MUSICIAN'", Role.class)
                    .getResultList().stream().findFirst().orElseGet(() -> {
                        Role created = Role.builder().name("ROLE_MUSICIAN").build(); em.persist(created); return created;
                    });
            String username = "httpfixture-" + UUID.randomUUID().toString().substring(0, 8);
            String email = username + "@example.invalid";
            User user = User.builder().username(username).email(email).password(encoder.encode(OLD_PASSWORD))
                    .status(UserStatus.ACTIVE).emailVerified(true).roles(Set.of(role)).build();
            em.persist(user); em.flush(); return new Actor(user.getId(), username, email);
        });
    }
    private String login(Actor actor, String password) throws Exception {
        var response = request("POST", "/api/v1/auth/login", null, Map.of("username", actor.username, "password", password), 200);
        String token = json.readTree(response.body()).at("/data/token").asText();
        assertThat(token).isNotBlank(); return token;
    }
    private HttpResponse<String> request(String method, String path, String token, Object body, int expected) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() :
                HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("%s %s status (body intentionally omitted)", method, path).isEqualTo(expected);
        return response;
    }
    private void assertUnavailable(HttpResponse<String> response, Actor actor) throws Exception {
        assertThat(response.headers().firstValue("Retry-After")).contains("5");
        assertThat(json.readTree(response.body()).path("code").asInt()).isEqualTo(ErrorType.AUTH_RATE_LIMIT_UNAVAILABLE.getCode());
        assertThat(response.body()).doesNotContain(actor.username, actor.email, OLD_PASSWORD, "Redis", "Lettuce", "127.0.0.1");
    }
    private void assertOwnedRedis() {
        assertThat(REDIS.getContainerInfo().getConfig().getLabels()).containsEntry("soundconnect.fixture", OWNER);
        assertThat(REDIS.getContainerId()).isNotBlank();
    }
    private record Actor(UUID id, String username, String email) { }
    private record Socket(StompSession session, BlockingQueue<Long> messages) { }
}
