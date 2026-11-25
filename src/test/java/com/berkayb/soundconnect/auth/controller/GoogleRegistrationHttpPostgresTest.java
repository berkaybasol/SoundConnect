package com.berkayb.soundconnect.auth.controller;

import com.berkayb.soundconnect.SoundConnectApplication;
import com.berkayb.soundconnect.auth.model.VerifiedGoogleIdentity;
import com.berkayb.soundconnect.auth.otp.service.OtpMailService;
import com.berkayb.soundconnect.auth.security.GoogleIdTokenValidator;
import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.entity.District;
import com.berkayb.soundconnect.modules.location.entity.Neighborhood;
import com.berkayb.soundconnect.modules.media.deletion.MediaDeletionDispatcher;
import com.berkayb.soundconnect.modules.media.entity.MediaAsset;
import com.berkayb.soundconnect.modules.media.enums.MediaContentAudience;
import com.berkayb.soundconnect.modules.media.enums.MediaKind;
import com.berkayb.soundconnect.modules.media.enums.MediaOwnerType;
import com.berkayb.soundconnect.modules.media.enums.MediaStatus;
import com.berkayb.soundconnect.modules.media.enums.MediaVisibility;
import com.berkayb.soundconnect.modules.media.repository.MediaAssetRepository;
import com.berkayb.soundconnect.modules.media.storage.StorageClient;
import com.berkayb.soundconnect.modules.notification.dlq.NotificationDlqBroker;
import com.berkayb.soundconnect.modules.role.entity.Permission;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.shared.config.ExternalRabbitConsumersTestIsolation;
import com.berkayb.soundconnect.shared.mail.adapter.MailSenderClient;
import com.berkayb.soundconnect.shared.mail.consumer.DlqMailJobConsumer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Production HTTP/security/registration/decision services on isolated Postgres and Redis. */
@SpringBootTest(classes = {SoundConnectApplication.class, GoogleRegistrationHttpPostgresTest.Isolation.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1", "spring.config.location=classpath:/application-test.yml", "spring.config.import=",
        "app.jwt.secret=google-registration-acceptance-disposable-test-key",
        "app.jwt.issuer=google-registration-fixture", "app.jwt.expiration=600000",
        "soundconnect.admin-notifications.venue-application-emails=reviewer@example.invalid",
        "soundconnect.admin-notifications.studio-application-emails=reviewer@example.invalid",
        "mail.dlq=google-registration-mail-dlq"})
@Import(ExternalRabbitConsumersTestIsolation.class)
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class GoogleRegistrationHttpPostgresTest {
    private static final String AUTH = "/api/v1/auth/";
    private static final String ACCOUNT_PASSWORD = "/api/v1/users/me/password";
    private static final String MARKET = "/api/v1/user/marketplace";
    private static final String COLLAB = "/api/v1/collabs";
    private static final String PASSWORD = "Fixture-password-48";
    private static final String REVIEWER = "fixture-reviewer";
    private static UUID cityId, districtId, neighborhoodId, reviewerId;

    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.2.5-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Isolation {
        @Bean LettuceConnectionFactory redisConnectionFactory() {
            return new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        }
        @Bean StringRedisTemplate stringRedisTemplate(LettuceConnectionFactory connection) {
            return new StringRedisTemplate(connection);
        }
        @Bean @DependsOn("entityManagerFactory")
        InitializingBean schemaForIsolatedDatabase(JdbcTemplate jdbc) {
            return () -> {
                try (var connection = jdbc.getDataSource().getConnection()) {
                    assertThat(connection.getMetaData().getURL()).isEqualTo(POSTGRES.getJdbcUrl());
                }
                for (String migration : List.of("2026-09-28-media-notification-identity.sql",
                        "2026-09-29-band-notification-identity.sql", "2026-10-06-application-mail-intents.sql",
                        "2026-11-22-application-reviews.sql")) {
                    jdbc.execute(Files.readString(Path.of("scripts/db", migration)));
                }
            };
        }
    }

    @MockitoBean GoogleIdTokenValidator googleIdentity;
    @MockitoBean OtpMailService otpMail;
    @MockitoBean StorageClient storage;
    @MockitoBean MediaDeletionDispatcher deletion;
    @MockitoBean RabbitTemplate rabbit;
    @MockitoBean(name = "rabbitConnectionFactory") CachingConnectionFactory brokerConnection;
    @MockitoBean(enforceOverride = true) NotificationDlqBroker dlq;
    @MockitoBean Jackson2JsonMessageConverter converter;
    @MockitoBean MailSenderClient mail;
    @MockitoBean com.berkayb.soundconnect.shared.mail.producer.MailProducer mailQueue;
    @MockitoBean DlqMailJobConsumer mailConsumer;

    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired MediaAssetRepository marketplaceAssets;
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeAll
    static void catalogAndReviewer(@Autowired EntityManager em, @Autowired PlatformTransactionManager transactions,
                                   @Autowired PasswordEncoder passwords) {
        new TransactionTemplate(transactions).executeWithoutResult(ignored -> {
            var city = City.builder().name("Application City").build();
            em.persist(city);
            var district = District.builder().city(city).name("Application District").build();
            em.persist(district);
            var neighborhood = Neighborhood.builder().district(district).name("Application Neighborhood").build();
            em.persist(neighborhood);
            cityId = city.getId();
            districtId = district.getId();
            neighborhoodId = neighborhood.getId();
            for (String role : List.of("LISTENER", "MUSICIAN", "VENUE", "STUDIO")) {
                em.persist(Role.builder().name("ROLE_" + role).build());
            }
            var adminRole = Role.builder().name("ROLE_ADMIN").build();
            for (String name : List.of("ADMIN_PANEL_ACCESS", "MANAGE_VENUE_APPLICATIONS", "MANAGE_STUDIO_APPLICATIONS")) {
                var permission = Permission.builder().name(name).build();
                em.persist(permission);
                adminRole.getPermissions().add(permission);
            }
            em.persist(adminRole);
            var reviewer = User.builder().username(REVIEWER).email("review@example.invalid")
                    .password(passwords.encode(PASSWORD)).status(UserStatus.ACTIVE).emailVerified(true)
                    .roles(new HashSet<>(Set.of(adminRole))).build();
            em.persist(reviewer);
            reviewerId = reviewer.getId();
            for (String username : List.of("second-reviewer", "revoked-reviewer")) {
                em.persist(User.builder().username(username).email(username + "@example.invalid")
                        .password(passwords.encode(PASSWORD)).status(UserStatus.ACTIVE).emailVerified(true)
                        .roles(new HashSet<>(Set.of(adminRole))).build());
            }
        });
    }

    @ParameterizedTest @ValueSource(strings = {"LISTENER", "MUSICIAN"})
    void personalProfilesAreUsableAfterConfirmationAndEachLogin(String role) throws Exception {
        String account = "google-" + role.toLowerCase(Locale.ROOT);
        stubIdentity(account);
        assertOnboarding(account);
        ObjectNode form = form(account, role);
        JsonNode created = complete(form, 200).path("data");
        assertActive(created, role);
        assertThat(created.path("requiresListenerProfileChoice").asBoolean()).isEqualTo(role.equals("LISTENER"));
        JsonNode retry = complete(form, 200).path("data");
        assertThat(retry.get("userId")).isEqualTo(created.get("userId"));
        if (role.equals("LISTENER")) {
            request("PATCH", "/api/v1/user/listener-profiles/me/visibility", created.get("token").asText(),
                    Map.of("visibilityMode", "GHOST", "expectedVersion", 0), 200);
        }
        JsonNode next = signIn(account, 200).path("data");
        assertActive(next, role);
        assertThat(next.path("requiresListenerProfileChoice").asBoolean()).isFalse();
        request("GET", ownerPath(role), next.get("token").asText(), null, 200);
        verifyNoInteractions(otpMail);
    }

    @Test
    void passwordChangeKeepsTheChangingDeviceSignedInAndRevokesPriorSessions() throws Exception {
        String account = "password-change-user";
        ObjectNode registration = form(account, "MUSICIAN");
        registration.remove(List.of("idToken", "consent"));
        registration.put("email", email(account)).put("password", PASSWORD).put("rePassword", PASSWORD);
        request("POST", AUTH + "register", null, registration, 201);
        var code = ArgumentCaptor.forClass(String.class);
        verify(otpMail).sendVerificationMail(eq(email(account)), code.capture());
        request("POST", AUTH + "verify-code", null, Map.of("email", email(account), "code", code.getValue()), 200);
        String first = passwordLogin(account, 200).at("/data/token").textValue();
        assertThat(first).isNotBlank();
        // The issuer serializes iat in seconds. Ensure the second login has its own bearer.
        long firstSecond = java.time.Instant.now().getEpochSecond();
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(3))
                .until(() -> java.time.Instant.now().getEpochSecond() > firstSecond);
        String second = passwordLogin(account, 200).at("/data/token").textValue();
        assertThat(second).isNotBlank().isNotEqualTo(first);

        request("GET", ACCOUNT_PASSWORD, null, null, 401);
        assertThat(request("GET", ACCOUNT_PASSWORD, first, null, 200).at("/data/provider").textValue()).isEqualTo("LOCAL");
        String replacement = "New-device-password-42";
        var wrong = Map.of("currentPassword", "incorrect-password", "newPassword", replacement);
        assertThat(request("POST", ACCOUNT_PASSWORD, first, wrong, 400).path("code").asInt()).isEqualTo(1123);
        var unchanged = Map.of("currentPassword", PASSWORD, "newPassword", PASSWORD);
        assertThat(request("POST", ACCOUNT_PASSWORD, first, unchanged, 400).path("code").asInt()).isEqualTo(1122);
        var tooManyBytes = Map.of("currentPassword", PASSWORD, "newPassword", "ü".repeat(37));
        request("POST", ACCOUNT_PASSWORD, first, tooManyBytes, 400);
        request("GET", ownerPath("MUSICIAN"), first, null, 200);
        request("GET", ownerPath("MUSICIAN"), second, null, 200);

        var change = Map.of("currentPassword", PASSWORD, "newPassword", replacement);
        request("POST", ACCOUNT_PASSWORD, null, change, 401);
        String renewed = request("POST", ACCOUNT_PASSWORD, first, change, 200).at("/data/token").textValue();
        assertThat(renewed).isNotBlank().isNotEqualTo(first).isNotEqualTo(second);
        request("GET", ownerPath("MUSICIAN"), renewed, null, 200);
        request("GET", ownerPath("MUSICIAN"), first, null, 401);
        request("GET", ownerPath("MUSICIAN"), second, null, 401);
        assertThat(passwordLogin(account, 401).required("code").asInt()).isEqualTo(1100);
        request("POST", AUTH + "login", null, Map.of("username", account, "password", replacement), 200);
    }

    private void prepareAccountErasureSchema() throws Exception {
        for (String migration : List.of("2026-09-09-overthinking-lifecycle.sql",
                "2026-09-10-overthinking-inbox-seen.sql", "2026-09-10-overthinking-profile-shares.sql",
                "2026-09-10-overthinking-production-safety.sql", "2026-09-10-listener-account-erasure.sql",
                "2026-09-10-tablegroup-profile-shares.sql", "2026-09-10-tablegroup-profile-share-history.sql",
                "2026-09-20-marketplace-domain.sql", "2026-09-24-studio-reservation-notification-outbox.sql",
                "2026-09-27-follow-notification-outbox.sql", "2026-11-24-user-blocks.sql",
                "2026-11-25-collab-account-erasure.sql", "2026-11-25-band-collab-closure.sql",
                "2026-11-25-business-account-erasure.sql", "2026-11-25-application-erasure-mail-cleanup.sql")) {
            jdbc.execute(Files.readString(Path.of("scripts/db", migration)));
        }
    }

    @Test
    void deletingListenerWithMarketplaceHistoryRemovesOnlyItsListings() throws Exception {
        prepareAccountErasureSchema();
        String owner = "former-musician", observer = "second-seller";
        stubIdentity(owner);
        stubIdentity(observer);
        var account = complete(form(owner, "MUSICIAN"), 200).required("data");
        var ownerId = UUID.fromString(account.required("userId").textValue());
        String ownerToken = account.required("token").textValue();
        String observerToken = complete(form(observer, "MUSICIAN"), 200).at("/data/token").textValue();
        String original = publishMarketplaceFixture(ownerToken, "Fixture guitar");
        String control = publishMarketplaceFixture(observerToken, "Fixture piano");
        String listings = MARKET + "/listings";
        var before = request("GET", listings, observerToken, null, 200).at("/data/content");
        assertThat(before.findValuesAsText("title")).contains("Fixture guitar", "Fixture piano");
        request("GET", listings + "/" + original, observerToken, null, 200);
        var ownListing = request("GET", listings + "/" + control, observerToken, null, 200).required("data");
        var report = Map.of("reason", "MISLEADING", "description", "Test report", "clientRequestId", UUID.randomUUID());
        UUID reportId = UUID.fromString(request("POST", listings + "/" + original + "/reports",
                observerToken, report, 201).at("/data/id").textValue());
        String reviewer = passwordLogin(REVIEWER, 200).at("/data/token").textValue();
        String queue = "/api/v1/admin/marketplace/reports?status=OPEN";
        var originalReport = request("GET", queue, reviewer, null, 200).at("/data/content/0");
        assertThat(originalReport.required("id").textValue()).isEqualTo(reportId.toString());
        assertThat(originalReport.at("/evidence/title").textValue()).isEqualTo("Fixture guitar");
        assertThat(originalReport.at("/evidence/photos/0/assetId").textValue()).isNotBlank();

        // The old listener-with-marketplace-history case stays covered alongside the musician Collab case.
        var erase = Map.of("confirmation", "DELETE", "googleIdToken", owner);
        switchFixtureToListener(ownerId);
        String listenerToken = signIn(owner, 200).at("/data/token").textValue();
        assertThat(request("DELETE", "/api/v1/users/me/account", listenerToken, erase, 200).required("data").asBoolean()).isTrue();
        request("GET", ownerPath("LISTENER"), listenerToken, null, 401);
        var after = request("GET", listings, observerToken, null, 200).at("/data/content").findValuesAsText("title");
        assertThat(after).contains("Fixture piano").doesNotContain("Fixture guitar");
        request("GET", listings + "/" + original, observerToken, null, 404);
        assertThat(request("GET", listings + "/" + control, observerToken, null, 200).required("data")).isEqualTo(ownListing);
        assertThat(jdbc.queryForObject("select count(*) from tbl_marketplace_listing where owner_user_id=?", Integer.class, ownerId)).isZero();
        var retained = request("GET", queue, reviewer, null, 200).at("/data/content/0");
        assertThat(retained.required("id")).isEqualTo(originalReport.required("id"));
        assertThat(retained.hasNonNull("listingId")).isFalse();
        assertThat(retained.required("evidence")).isEqualTo(originalReport.required("evidence"));
    }

    @Test
    void deletingAnAccountClosesItsPendingCollabAndCancelsItsJobForTheOtherMusician() throws Exception {
        prepareAccountErasureSchema();
        String departing = "collab-departing", remaining = "collab-remaining";
        stubIdentity(departing);
        stubIdentity(remaining);
        JsonNode account = complete(form(departing, "MUSICIAN"), 200).required("data");
        String firstToken = account.required("token").textValue();
        String otherToken = complete(form(remaining, "MUSICIAN"), 200).at("/data/token").textValue();
        UUID profileId = jdbc.queryForObject("select id from tbl_musician_profile where user_id=?", UUID.class,
                UUID.fromString(account.required("userId").textValue()));
        var trackAsset = marketplaceAssets.saveAndFlush(MediaAsset.builder().ownerType(MediaOwnerType.MUSICIAN_PROFILE)
                .ownerId(profileId).kind(MediaKind.AUDIO).visibility(MediaVisibility.PUBLIC)
                .status(MediaStatus.READY).mimeType("audio/mpeg").size(1200L).build());
        jdbc.update("insert into tbl_tracks(id,owner_type,owner_id,media_asset_id,title) values (?,'MUSICIAN_PROFILE',?,?,?)",
                UUID.randomUUID(), profileId, trackAsset.getId(), "Disposable rehearsal recording");
        jdbc.update("insert into tbl_setlist(id,musician_profile_id,name) values (?,?,?)", UUID.randomUUID(), profileId, "Personal repertoire");
        String firstActor = request("GET", COLLAB + "/actors/me", firstToken, null, 200).at("/data/0/actorId").textValue();
        String otherActor = request("GET", COLLAB + "/actors/me", otherToken, null, 200).at("/data/0/actorId").textValue();
        String firstListing = publishCollabFixture(firstToken, firstActor, "First rehearsal");
        String otherListing = publishCollabFixture(otherToken, otherActor, "Second rehearsal");
        var pending = submitCollabApplication(otherToken, otherActor, firstListing, "+905550000001");
        var accepted = submitCollabApplication(firstToken, firstActor, otherListing, "+905550000002");
        String incomingPath = COLLAB + "/me/listings/" + otherListing + "/applications";
        assertThat(request("GET", incomingPath, otherToken, null, 200).at("/data/content/0/phoneNumber").textValue())
                .isEqualTo("+905550000002");
        String job = request("POST", COLLAB + "/applications/" + accepted.required("id").textValue() + "/accept", otherToken,
                Map.of("expectedVersion", accepted.required("version").longValue()), 200).at("/data/id").textValue();

        request("DELETE", "/api/v1/users/me/account", firstToken, Map.of("confirmation", "DELETE", "googleIdToken", departing), 200);
        request("GET", ownerPath("MUSICIAN"), firstToken, null, 401);
        assertThat(jdbc.queryForObject("select count(*) from tbl_musician_profile where user_id=?",
                Integer.class, UUID.fromString(account.required("userId").textValue()))).isZero();
        assertThat(jdbc.queryForObject("select count(*) from tbl_tracks where owner_id=?", Integer.class, profileId)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from tbl_setlist where musician_profile_id=?", Integer.class, profileId)).isZero();
        assertThat(marketplaceAssets.findById(trackAsset.getId()).orElseThrow().getStatus()).isEqualTo(MediaStatus.DELETION_PENDING);

        var result = request("GET", COLLAB + "/me/applications", otherToken, null, 200).at("/data/content/0");
        assertThat(result.required("id")).isEqualTo(pending.required("id"));
        assertThat(result.required("status").textValue()).isEqualTo("INVALIDATED_BY_LISTING_CLOSURE");
        assertThat(result.at("/listing/status").textValue()).isEqualTo("CLOSED");
        JsonNode closed = request("GET", COLLAB + "/" + firstListing, otherToken, null, 200).required("data");
        assertThat(closed.required("status").textValue()).isEqualTo("CLOSED");
        assertThat(closed.required("publisher").required("profileAvailable").booleanValue()).isFalse();
        assertThat(request("GET", COLLAB, otherToken, null, 200).at("/data/content").findValuesAsText("id"))
                .doesNotContain(firstListing);
        var cancelled = request("GET", COLLAB + "/me/jobs?status=CANCELLED", otherToken, null, 200).at("/data/content/0");
        assertThat(cancelled.required("id").textValue()).isEqualTo(job);
        assertThat(cancelled.required("status").textValue()).isEqualTo("CANCELLED");
        var history = request("GET", incomingPath, otherToken, null, 200).at("/data/content/0");
        assertThat(history.required("id")).isEqualTo(accepted.required("id"));
        assertThat(history.hasNonNull("phoneNumber")).isFalse();
        assertThat(history.required("applicant").hasNonNull("contactUserId")).isFalse();
    }

    private JsonNode submitCollabApplication(String token, String actor, String listing, String phone) throws Exception {
        return request("POST", COLLAB + "/" + listing + "/applications", token,
                Map.of("clientRequestId", UUID.randomUUID(), "applicantActorId", actor, "phoneNumber", phone), 201).required("data");
    }

    private String publishCollabFixture(String token, String actor, String title) throws Exception {
        var draft = request("POST", COLLAB + "/drafts", token, Map.of("clientRequestId", UUID.randomUUID(),
                "publisherActorId", actor, "cadence", "REGULAR", "wantedType", "MUSICIAN", "branch", "VOCAL",
                "title", title, "description", "Vocalist wanted for a weekly rehearsal.", "cityId", cityId), 201).required("data");
        String listing = draft.required("id").textValue();
        request("POST", COLLAB + "/" + listing + "/publish", token, Map.of("expectedVersion", draft.required("version").longValue()), 200);
        return listing;
    }

    @ParameterizedTest
    @CsvSource({"LISTENER,first", "MUSICIAN,suspended", "VENUE,pending", "VENUE,rejected",
            "STUDIO,pending", "STUDIO,rejected", "VENUE,approved", "STUDIO,approved"})
    void mailboxErasureWorksWithoutAnApplicationSession(String role, String state) throws Exception {
        prepareAccountErasureSchema();
        String account = "web-" + role.toLowerCase(Locale.ROOT) + "-" + state;
        stubIdentity(account);
        var created = complete(form(account, role), 200).required("data");
        UUID user = UUID.fromString(created.required("userId").textValue());
        if (state.equals("first")) assertThat(created.path("requiresListenerProfileChoice").booleanValue()).isTrue();
        if (state.equals("suspended")) jdbc.update("update tbl_user set access_suspended=true where id=?", user);
        if (state.equals("approved") || state.equals("rejected")) {
            review(role, state.toUpperCase(Locale.ROOT), UUID.fromString(created.required("applicationId").textValue()));
        }
        String base = "/api/v1/public/account-deletion/";
        String email = email(account);
        String proof = verifyMailbox(account);
        request("POST", base + "confirm", null, Map.of("email", email("another"), "proof", proof, "confirmation", "DELETE"), 403);
        assertThat(accounts(account)).isOne();
        var command = Map.of("email", email, "proof", proof, "confirmation", "DELETE");
        request("POST", base + "confirm", null, command, 204);
        request("POST", base + "confirm", null, command, 403);
        assertThat(accounts(account)).isZero();
        assertThat(jdbc.queryForObject("select erased_at is not null from tbl_user where id=?", Boolean.class, user)).isTrue();
        if (created.hasNonNull("applicationId")) {
            UUID application = UUID.fromString(created.required("applicationId").textValue());
            assertThat(jdbc.queryForObject("select count(*) from tbl_application_mail_intent where application_id=?", Integer.class, application)).isZero();
        }
    }

    @ParameterizedTest @ValueSource(strings={"VENUE", "STUDIO"})
    void mailboxErasureAndReviewerDecisionSerialize(String role) throws Exception {
        prepareAccountErasureSchema();
        String account = "web-race-" + role.toLowerCase(Locale.ROOT);
        stubIdentity(account);
        var result = complete(form(account, role), 200).required("data");
        String token = passwordLogin(REVIEWER, 200).at("/data/token").textValue();
        String approval = "/api/v1/admin/" + role.toLowerCase(Locale.ROOT) + "-applications/approve/" + result.required("applicationId").textValue();
        String proof = verifyMailbox(account);
        var command = Map.of("email", email(account), "proof", proof, "confirmation", "DELETE");
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        try (var threads = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var reviewer = threads.submit(() -> {
                barrier.await(10, java.util.concurrent.TimeUnit.SECONDS);
                // The application row can disappear immediately after the successful HTTP response.
                return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + approval))
                        .header("Authorization", "Bearer " + token).POST(HttpRequest.BodyPublishers.noBody())
                        .timeout(Duration.ofSeconds(20)).build(), HttpResponse.BodyHandlers.discarding()).statusCode();
            });
            var erased = threads.submit(() -> {
                barrier.await(10, java.util.concurrent.TimeUnit.SECONDS);
                return request("POST", "/api/v1/public/account-deletion/confirm", null, command, 204);
            });
            erased.get(30, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(reviewer.get(30, java.util.concurrent.TimeUnit.SECONDS)).isIn(200,404,409,410);
        }
        assertThat(accounts(account)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from user_roles where user_id=?", Integer.class,
                UUID.fromString(result.required("userId").textValue()))).isZero();
    }

    private String verifyMailbox(String account) throws Exception {
        String base = "/api/v1/public/account-deletion/";
        String email = email(account);
        request("POST", base + "request", null, Map.of("email", email), 202);
        var captured = ArgumentCaptor.forClass(com.berkayb.soundconnect.shared.mail.dto.MailSendRequest.class);
        verify(mailQueue, org.mockito.Mockito.atLeastOnce()).send(captured.capture());
        var message = captured.getAllValues().stream().filter(job -> email.equals(job.to()) &&
                "SoundConnect hesap silme kodu".equals(job.subject())).findFirst().orElseThrow();
        var matcher = java.util.regex.Pattern.compile("[0-9]{6}").matcher(message.textBody());
        assertThat(matcher.find()).isTrue();
        String code = matcher.group();
        request("POST", base + "verify", null, Map.of("email", email, "code", code.equals("000000") ? "999999" : "000000"), 403);
        var preview = request("POST", base + "verify", null, Map.of("email", email, "code", code), 200);
        assertThat(preview.required("username").textValue()).isEqualTo(account);
        request("POST", base + "verify", null, Map.of("email", email, "code", code), 403);
        return preview.required("proof").textValue();
    }

    @Test void suspendedLocalAccountCanUseItsCurrentPasswordAndAResetInvalidatesItsEarlierProof() throws Exception {
        prepareAccountErasureSchema();
        String username = "web-password-owner";
        ObjectNode registration = form(username, "MUSICIAN");
        registration.remove(List.of("idToken", "consent"));
        String email = email(username);
        registration.put("email", email).put("password", PASSWORD).put("rePassword", PASSWORD);
        request("POST", AUTH + "register", null, registration, 201);
        var code = ArgumentCaptor.forClass(String.class);
        verify(otpMail).sendVerificationMail(eq(email), code.capture());
        request("POST", AUTH + "verify-code", null, Map.of("email", email, "code", code.getValue()), 200);
        jdbc.update("update tbl_user set access_suspended=true where email=?", email);
        String base = "/api/v1/public/account-deletion/";
        request("POST", base + "password", null, Map.of("email", email, "currentPassword", "wrong"), 403);
        var credentials = Map.of("email", email, "currentPassword", PASSWORD);
        String old = request("POST", base + "password", null, credentials, 200).required("proof").textValue();
        jdbc.update("update tbl_user set session_version=session_version+1 where email=?", email);
        request("POST", base + "confirm", null, Map.of("email", email, "proof", old, "confirmation", "DELETE"), 403);
        var current = request("POST", base + "password", null, credentials, 200);
        assertThat(current.required("username").textValue()).isEqualTo(username);
        request("POST", base + "confirm", null, Map.of("email", email, "proof", current.required("proof").textValue(),
                "confirmation", "DELETE"), 204);
        assertThat(accounts(username)).isZero();
    }

    private void switchFixtureToListener(UUID userId) {
        assertThat(jdbc.update("delete from tbl_musician_profile where user_id=?", userId)).isOne();
        assertThat(jdbc.update("update user_roles set role_id=(select id from tbl_role where name='ROLE_LISTENER') where user_id=?",
                userId)).isOne();
        assertThat(jdbc.update("""
                insert into "tbl_listener-profile"(id, created_at, updated_at, user_id, name, visibility_mode,
                    visibility_choice_completed, version, playlist_revision)
                values (?, now(), now(), ?, 'Former musician', 'STANDARD', true, 0, 0)
                """, UUID.randomUUID(), userId)).isOne();
    }

    private String publishMarketplaceFixture(String token, String title) throws Exception {
        var draft = request("POST", MARKET + "/drafts", token, Map.of("clientRequestId", UUID.randomUUID()), 201).required("data");
        UUID listing = UUID.fromString(draft.required("id").textValue());
        // The image processor is outside this HTTP fixture. Attach its persisted READY output.
        MediaAsset image = marketplaceAssets.saveAndFlush(MediaAsset.builder()
                .ownerType(MediaOwnerType.MARKETPLACE).ownerId(listing).kind(MediaKind.IMAGE)
                .visibility(MediaVisibility.PRIVATE).contentAudience(MediaContentAudience.BACKSTAGE)
                .status(MediaStatus.READY).mimeType("image/jpeg").size(1000L).build());
        image.setStorageKey("protected/private-verified/media/" + image.getId() + "/attempts/" + UUID.randomUUID() + "/source.jpg");
        marketplaceAssets.saveAndFlush(image);
        UUID category = jdbc.queryForObject("select id from tbl_marketplace_category where code='ELECTRIC_GUITAR'", UUID.class);
        var item = Map.of("expectedVersion", draft.required("version").longValue(), "title", title, "description", "Used test instrument",
                "categoryId", category, "condition", "USED", "priceMinor", 50000L, "districtId", districtId,
                "photoIds", List.of(image.getId()), "negotiable", false, "deliveryMethod", "PICKUP");
        String path = MARKET + "/listings/" + listing;
        var version = request("PUT", path, token, item, 200).at("/data/version").longValue();
        request("POST", path + "/publish", token, Map.of("expectedVersion", version), 200);
        return listing.toString();
    }

    @Test
    void googleAccountKeepsItsSessionAndCannotSetALocalPassword() throws Exception {
        String account = "google-password-change";
        stubIdentity(account);
        String token = complete(form(account, "MUSICIAN"), 200).at("/data/token").textValue();
        var lookup = "select password, session_version from tbl_user where user_name=?";
        var before = jdbc.queryForMap(lookup, account);
        assertThat(request("GET", ACCOUNT_PASSWORD, token, null, 200).at("/data/provider").textValue()).isEqualTo("GOOGLE");
        var change = Map.of("currentPassword", PASSWORD, "newPassword", "New-device-password-42");
        assertThat(request("POST", ACCOUNT_PASSWORD, token, change, 409).required("code").asInt()).isEqualTo(1121);
        assertThat(jdbc.queryForMap(lookup, account)).isEqualTo(before);
        request("GET", ownerPath("MUSICIAN"), token, null, 200);
    }

    @ParameterizedTest @CsvSource({"VENUE,APPROVED", "VENUE,REJECTED", "STUDIO,APPROVED", "STUDIO,REJECTED"})
    void businessApplicationWaitsForSeparateReviewAndReopensWithItsDecision(String role, String decision) throws Exception {
        boolean venue = role.equals("VENUE");
        String account = (role + "-" + decision).toLowerCase(Locale.ROOT);
        stubIdentity(account);
        assertOnboarding(account);
        ObjectNode form = form(account, role);
        JsonNode pending = complete(form, 200).path("data");
        UUID id = UUID.fromString(pending.required("applicationId").asText());
        UUID applicant = UUID.fromString(pending.required("userId").asText());
        assertThat(pending.path("status").asText()).isEqualTo("PENDING_" + role + "_REQUEST");
        assertThat(pending.required("roles").isEmpty()).isTrue();
        assertThat(pending.required("permissions").isEmpty()).isTrue();
        assertThat(pending.required("requiresGoogleProfileCompletion").booleanValue()).isFalse();
        assertThat(pending.hasNonNull("token")).isEqualTo(venue);
        assertApplicationFields(role, applicant, id, account);
        assertThat(complete(form, 200).at("/data/applicationId")).isEqualTo(pending.get("applicationId"));
        assertThat(signIn(account, 200).at("/data/applicationId")).isEqualTo(pending.get("applicationId"));
        assertThat(complete(form(account, "MUSICIAN"), 409).get("code").asInt()).isEqualTo(1004);
        assertThat(jdbc.queryForObject("select count(*) from tbl_application_mail_intent where application_id=? and decision='CREATED'",
                Integer.class, id)).isOne();
        assertThat(profileCount(role, applicant)).isZero();
        assertThat(accounts(account)).isOne();

        if (venue) {
            assertThat(pending.path("sessionScope").asText()).isEqualTo("VENUE_APPLICATION");
            String scoped = pending.get("token").asText();
            assertThat(decisionDetail(id, scoped).get("status").asText()).isEqualTo("PENDING");
            request("GET", ownerPath(role), scoped, null, 403);
        }
        var base = "/api/v1/admin/" + role.toLowerCase(Locale.ROOT) + "-applications";
        String adminToken = passwordLogin(REVIEWER, 200).at("/data/token").asText();
        var listed = request("GET", base + "/pending?size=100", adminToken, null, 200).at("/data/content");
        assertThat(listed.findValuesAsText("id")).contains(id.toString());
        assertThat(listed.findValues("phone")).isEmpty();
        assertThat(listed.findValues("rejectionReason")).isEmpty();
        var detail = request("GET", base + "/review/" + id, adminToken, null, 200).required("data");
        assertThat(detail.required("phone").asText()).isEqualTo("05550001122");
        assertThat(detail.required("status").asText()).isEqualTo("PENDING");
        request("POST", base + "/reject/" + id, adminToken, Map.of("reason", "   "), 400);
        JsonNode reviewed = review(role, decision, id);
        var receipt = request("GET", base + "/review/" + id, adminToken, null, 200).required("data");
        assertThat(receipt.required("status").asText()).isEqualTo(decision);
        assertThat(receipt.required("reviewedByUsername").asText()).isEqualTo(REVIEWER);
        assertThat(UUID.fromString(receipt.required("decisionId").asText())).isNotNull();
        assertThat(receipt.required("decisionDate").asText()).endsWith("Z");
        assertThat(receipt.hasNonNull("rejectionReason")).isEqualTo(decision.equals("REJECTED"));
        request("POST", base + "/reject/" + id, adminToken, Map.of("reason", "Second decision"), 409);
        assertThat(request("GET", base + "/review/" + id, adminToken, null, 200).required("data")).isEqualTo(receipt);
        if (!venue && decision.equals("APPROVED")) {
            assertThat(jdbc.queryForObject("select count(*) from tbl_studio_applications a join tbl_studio_profile p "
                    + "on p.id=a.approved_studio_profile_id where a.id=? and p.user_id=?", Integer.class, id, applicant)).isOne();
        }
        assertThat(reviewed.path("status").asText()).isEqualTo(decision);
        assertThat(reviewed.hasNonNull("decisionDate")).isTrue();
        if (!venue) {
            assertThat(reviewed.path("reviewedById").asText()).isEqualTo(reviewerId.toString());
        }
        if (decision.equals("APPROVED")) {
            JsonNode active = signIn(account, 200).path("data");
            assertActive(active, role);
            assertThat(active.path("userId").asText()).isEqualTo(applicant.toString());
            assertThat(profileCount(role, applicant)).isOne();
            request("GET", ownerPath(role), active.required("token").asText(), null, 200);
            if (venue) {
                assertThat(decisionDetail(id, pending.get("token").asText()).get("status").asText()).isEqualTo("APPROVED");
                JsonNode promoted = request("POST", decisionPath(id) + "/promote", pending.get("token").asText(), null, 200).path("data");
                assertActive(promoted, role);
            }
        } else if (venue) {
            JsonNode rejected = signIn(account, 200).path("data");
            assertThat(rejected.path("sessionScope").asText()).isEqualTo("VENUE_APPLICATION");
            assertThat(rejected.get("applicationId")).isEqualTo(pending.get("applicationId"));
            var ownDecision = decisionDetail(id, rejected.get("token").asText());
            assertThat(ownDecision.required("status").asText()).isEqualTo("REJECTED");
            assertThat(ownDecision.required("rejectionReason").asText()).isEqualTo("missing details");
            assertThat(ownDecision.has("reviewedByUsername")).isFalse();
            assertThat(profileCount(role, applicant)).isZero();
        } else {
            assertThat(reviewed.path("rejectionReason").asText()).isEqualTo("missing details");
            var outcome = signIn(account, 403);
            assertThat(outcome.required("code").asInt()).isEqualTo(1112);
            assertThat(outcome.required("rejectionReason").asText()).isEqualTo("missing details");
            assertThat(outcome.required("businessName").asText()).isEqualTo("fixture " + account);
            assertThat(profileCount(role, applicant)).isZero();
        }
        verifyNoInteractions(otpMail);
    }

    @ParameterizedTest @ValueSource(strings = {"LISTENER", "MUSICIAN", "VENUE", "STUDIO"})
    void normalRegistrationStillRequiresOtpAndKeepsItsProfileAndApplicationRules(String role) throws Exception {
        String account = "normal-" + role.toLowerCase(Locale.ROOT);
        ObjectNode body = form(account, role);
        body.remove(List.of("idToken", "consent"));
        body.put("email", email(account)).put("password", PASSWORD).put("rePassword", PASSWORD);
        JsonNode registered = request("POST", AUTH + "register", null, body, 201).path("data");
        assertThat(registered.path("mailQueued").asBoolean()).isTrue();
        JsonNode unfinished = passwordLogin(account, 403);
        assertThat(unfinished.required("code").asInt()).isEqualTo(1113);
        assertThat(unfinished.required("details")).isEqualTo(json.createArrayNode().add(email(account)));
        var code = ArgumentCaptor.forClass(String.class);
        verify(otpMail).sendVerificationMail(eq(email(account)), code.capture());
        request("POST", AUTH + "verify-code", null, Map.of("email", email(account), "code", code.getValue()), 200);
        stubIdentity(account);
        assertThat(signIn(account, 409).required("code").asInt()).isEqualTo(1116);
        if (registered.hasNonNull("applicationId")) {
            UUID application = UUID.fromString(registered.get("applicationId").asText());
            if (role.equals("VENUE")) {
                JsonNode restricted = passwordLogin(account, 200).path("data");
                assertThat(restricted.path("sessionScope").asText()).isEqualTo("VENUE_APPLICATION");
                assertThat(decisionDetail(application, restricted.get("token").asText()).get("status").asText()).isEqualTo("PENDING");
            } else {
                assertThat(passwordLogin(account, 403).required("code").asInt()).isEqualTo(1106);
            }
            review(role, "APPROVED", application);
        }
        JsonNode session = passwordLogin(account, 200).path("data");
        assertActive(session, role);
        assertThat(session.required("requiresListenerProfileChoice").asBoolean()).isEqualTo(role.equals("LISTENER"));
        assertThat(jdbc.queryForObject("select provider from tbl_user where email=?", String.class, email(account))).isEqualTo("LOCAL");
    }

    @ParameterizedTest @ValueSource(strings = {"VENUE", "STUDIO"})
    void locationFailureRollsBackNewIdentityAndCanBeCorrected(String role) throws Exception {
        String account = "recover-" + role.toLowerCase(Locale.ROOT);
        stubIdentity(account);
        ObjectNode invalid = form(account, role).put("neighborhoodId", UUID.randomUUID().toString());
        int intentsBefore = jdbc.queryForObject("select count(*) from tbl_application_mail_intent", Integer.class);
        assertThat(complete(invalid, 404).required("code").asInt()).isEqualTo(6006);
        assertOnboarding(account);
        assertThat(jdbc.queryForObject("select count(*) from tbl_application_mail_intent", Integer.class)).isEqualTo(intentsBefore);
        assertThat(complete(form(account, role), 200).at("/data/status").asText()).isEqualTo("PENDING_" + role + "_REQUEST");
    }

    @Test void simultaneousConfirmationsCreateOnlyOneApplication() throws Exception {
        String account = "parallel-venue";
        stubIdentity(account);
        var start = new java.util.concurrent.CyclicBarrier(2);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<UUID> submit = () -> {
                start.await(10, java.util.concurrent.TimeUnit.SECONDS);
                return UUID.fromString(complete(form(account, "VENUE"), 200).at("/data/applicationId").asText());
            };
            var first = executor.submit(submit);
            var second = executor.submit(submit);
            UUID id = first.get(30, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(second.get(30, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(id);
            assertThat(jdbc.queryForObject("select count(*) from tbl_venue_applications where user_id=(select id from tbl_user where email=?)",
                    Integer.class, email(account))).isOne();
            assertThat(jdbc.queryForObject("select count(*) from tbl_application_mail_intent where application_id=?", Integer.class, id)).isOne();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"VENUE", "STUDIO"})
    void concurrentOppositeDecisionsPersistOnlyTheWinningReviewerAndEffects(String role) throws Exception {
        String account = "race-" + role.toLowerCase(Locale.ROOT);
        stubIdentity(account);
        var registration = complete(form(account, role), 200).required("data");
        UUID applicant = UUID.fromString(registration.required("userId").asText());
        UUID id = UUID.fromString(registration.required("applicationId").asText());
        String base = "/api/v1/admin/" + role.toLowerCase(Locale.ROOT) + "-applications";
        String token = passwordLogin(REVIEWER, 200).at("/data/token").asText();
        String otherToken = passwordLogin("second-reviewer", 200).at("/data/token").asText();
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var approved = executor.submit(() -> {
                barrier.await(10, java.util.concurrent.TimeUnit.SECONDS);
                return request("POST", base + "/approve/" + id, token, null, 200, 409).path("code").asInt();
            });
            var rejected = executor.submit(() -> {
                barrier.await(10, java.util.concurrent.TimeUnit.SECONDS);
                return request("POST", base + "/reject/" + id, otherToken, Map.of("reason", "Adres eksik."), 200, 409).path("code").asInt();
            });
            int approvalCode = approved.get(30, java.util.concurrent.TimeUnit.SECONDS);
            int rejectionCode = rejected.get(30, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(List.of(approvalCode, rejectionCode)).containsExactlyInAnyOrder(200, 1602);
            var finalRow = request("GET", base + "/review/" + id, token, null, 200).required("data");
            assertThat(finalRow.required("status").asText()).isEqualTo(approvalCode == 200 ? "APPROVED" : "REJECTED");
            assertThat(finalRow.required("reviewedByUsername").asText()).isEqualTo(approvalCode == 200 ? REVIEWER : "second-reviewer");
            assertThat(finalRow.hasNonNull("rejectionReason")).isEqualTo(rejectionCode == 200);
            assertThat(profileCount(role, applicant)).isEqualTo(approvalCode == 200 ? 1 : 0);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"VENUE", "STUDIO"})
    void failedDecisionPersistenceRollsBackProfileRoleAndReceipt(String role) throws Exception {
        String account = "failed-review-" + role.toLowerCase(Locale.ROOT);
        stubIdentity(account);
        var registration = complete(form(account, role), 200).required("data");
        UUID applicant = UUID.fromString(registration.required("userId").asText());
        UUID id = UUID.fromString(registration.required("applicationId").asText());
        boolean venue = role.equals("VENUE");
        String table = venue ? "tbl_venue_applications" : "tbl_studio_applications";
        String base = "/api/v1/admin/" + role.toLowerCase(Locale.ROOT) + "-applications";
        String token = passwordLogin(REVIEWER, 200).at("/data/token").asText();
        // Isolated Testcontainers database. Only this fixture row rejects a decision write.
        jdbc.execute("alter table " + table + " add constraint fixture_fail_review check "
                + "(id <> '" + id + "' or status='PENDING')");
        try {
            request("POST", base + "/approve/" + id, token, null, 409);
        } finally {
            jdbc.execute("alter table " + table + " drop constraint fixture_fail_review");
        }
        var row = request("GET", base + "/review/" + id, token, null, 200).required("data");
        assertThat(row.required("status").asText()).isEqualTo("PENDING");
        assertThat(row.hasNonNull("reviewedByUsername") || row.hasNonNull("decisionDate")).isFalse();
        assertThat(profileCount(role, applicant)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from user_roles where user_id=?", Integer.class, applicant)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from tbl_application_mail_intent where application_id=? "
                + "and decision <> 'CREATED'", Integer.class, id)).isZero();
    }

    @Test
    void blockedReviewCannotUseASessionRevokedWhileWaitingForTheActorLock() throws Exception {
        String applicant = "blocked-review-test";
        stubIdentity(applicant);
        var data = complete(form(applicant, "VENUE"), 200).required("data");
        UUID id = UUID.fromString(data.required("applicationId").asText());
        String reviewer = "revoked-reviewer";
        String token = passwordLogin(reviewer, 200).at("/data/token").asText();
        try (var blocker = POSTGRES.createConnection(""); var sql = blocker.createStatement();
             var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            blocker.setAutoCommit(false);
            int pid;
            try (var rows = sql.executeQuery("select pg_backend_pid() from tbl_user where user_name='revoked-reviewer' for update")) {
                assertThat(rows.next()).isTrue();
                pid = rows.getInt(1);
            }
            var result = executor.submit(() -> request("POST", "/api/v1/admin/venue-applications/approve/" + id, token, null, 403));
            try {
                org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(12)).until(() -> Boolean.TRUE.equals(
                        jdbc.queryForObject("select exists (select 1 from pg_stat_activity a where ?=any(pg_blocking_pids(a.pid)))",
                                Boolean.class, pid)));
                assertThat(sql.executeUpdate("update tbl_user set session_version=session_version+1 where user_name='revoked-reviewer'")).isOne();
                blocker.commit();
            } finally {
                blocker.rollback();
            }
            assertThat(result.get(25, java.util.concurrent.TimeUnit.SECONDS).path("code").asInt()).isPositive();
            assertThat(jdbc.queryForObject("select status from tbl_venue_applications where id=?", String.class, id)).isEqualTo("PENDING");
            assertThat(jdbc.queryForObject("select decision_id is null from tbl_venue_applications where id=?", Boolean.class, id)).isTrue();
        }
    }

    @Test
    void reviewMigrationReplaysOverExistingRowsWithoutReconstructingHistory() throws Exception {
        String migration = Files.readString(Path.of("scripts/db/2026-11-22-application-reviews.sql"));
        // A separate legacy schema on the disposable container, independent of the ORM-created current schema.
        try (var connection = POSTGRES.createConnection(""); var sql = connection.createStatement()) {
            sql.execute("create schema legacy_application_reviews");
            sql.execute("set search_path to legacy_application_reviews");
            sql.execute("create table tbl_venue_applications (id uuid primary key, status varchar, phone varchar(15), decision_date timestamp)");
            sql.execute("create table tbl_studio_applications (id uuid primary key, applicant_id uuid, status varchar, decision_date timestamp,"
                    + " reviewed_by_id uuid, rejection_reason varchar(500))");
            UUID oldVenue = UUID.randomUUID(), oldStudio = UUID.randomUUID(), oldReviewer = UUID.randomUUID();
            try (var venue = connection.prepareStatement("insert into tbl_venue_applications values (?, 'APPROVED', null, timestamp '2026-11-01 10:00:00')");
                 var studio = connection.prepareStatement("insert into tbl_studio_applications values (?, ?, 'REJECTED', timestamp '2026-11-02 14:00:00', ?, 'Eski gerekçe')")) {
                venue.setObject(1, oldVenue);
                venue.executeUpdate();
                studio.setObject(1, oldStudio);
                studio.setObject(2, UUID.randomUUID());
                studio.setObject(3, oldReviewer);
                studio.executeUpdate();
            }
            sql.execute(migration);
            sql.execute(migration);
            try (var rows = sql.executeQuery("select * from tbl_venue_applications")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getObject("id")).isEqualTo(oldVenue);
                assertThat(rows.getString("status")).isEqualTo("APPROVED");
                assertThat(rows.getTimestamp("decision_date").toLocalDateTime()).isEqualTo("2026-11-01T10:00:00");
                assertThat(rows.getObject("decision_id")).isNull();
                assertThat(rows.getObject("reviewed_by_id")).isNull();
                assertThat(rows.getString("rejection_reason")).isNull();
                assertThat(rows.getString("phone")).isNull();
                assertThat(rows.next()).isFalse();
            }
            try (var rows = sql.executeQuery("select * from tbl_studio_applications")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getObject("id")).isEqualTo(oldStudio);
                assertThat(rows.getString("status")).isEqualTo("REJECTED");
                assertThat(rows.getObject("reviewed_by_id")).isEqualTo(oldReviewer);
                assertThat(rows.getString("rejection_reason")).isEqualTo("Eski gerekçe");
                assertThat(rows.getObject("approved_studio_profile_id")).isNull();
                assertThat(rows.getObject("decision_id")).isNull();
                assertThat(rows.next()).isFalse();
            }
        }
    }

    private void assertApplicationFields(String role, UUID userId, UUID applicationId, String account) {
        boolean venue = role.equals("VENUE");
        String fields = venue ? "venue_name as name, venue_address as address, user_id as applicant from tbl_venue_applications" :
                "studio_name as name, studio_address as address, applicant_id as applicant from tbl_studio_applications";
        var row = jdbc.queryForMap("select status, phone, city_id, district_id, neighborhood_id, " + fields + " where id=?", applicationId);
        assertThat(row).containsEntry("status", "PENDING").containsEntry("applicant", userId)
                .containsEntry("name", "fixture " + account).containsEntry("address", "Fixture street 15")
                .containsEntry("phone", "05550001122").containsEntry("city_id", cityId)
                .containsEntry("district_id", districtId).containsEntry("neighborhood_id", neighborhoodId);
    }
    private int profileCount(String role, UUID applicant) {
        String sql = role.equals("STUDIO") ? "select count(*) from tbl_studio_profile where user_id=?" :
                "select count(*) from tbl_venue_profile p join tbl_venues v on p.venue_id=v.id where v.owner_id=?";
        return jdbc.queryForObject(sql, Integer.class, applicant);
    }
    private JsonNode review(String role, String decision, UUID application) throws Exception {
        String adminToken = passwordLogin(REVIEWER, 200).at("/data/token").asText();
        String operation = decision.equals("APPROVED") ? "approve" : "reject";
        String path = "/api/v1/admin/" + role.toLowerCase(Locale.ROOT) + "-applications/" + operation + "/" + application;
        boolean reject = decision.equals("REJECTED");
        Object body = reject ? Map.of("reason", "missing details") : null;
        return request("POST", path, adminToken, body, 200).path("data");
    }
    private static String decisionPath(UUID id) { return "/api/v1/venue-application-session/applications/" + id; }
    private JsonNode decisionDetail(UUID id, String token) throws Exception {
        return request("GET", decisionPath(id), token, null, 200).path("data");
    }


    private static String ownerPath(String role) {
        return "/api/v1/user/" + role.toLowerCase(Locale.ROOT) + "-profiles/me";
    }

    private static void assertActive(JsonNode data, String role) {
        assertThat(data.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(data.required("token").textValue()).isNotBlank();
        assertThat(data.required("roles").size()).isEqualTo(1);
        assertThat(data.get("roles").get(0).textValue()).isEqualTo("ROLE_" + role);
        assertThat(data.path("sessionScope").isNull() || data.path("sessionScope").isMissingNode()).isTrue();
        assertThat(data.path("requiresGoogleProfileCompletion").asBoolean()).isFalse();
    }

    private ObjectNode form(String account, String role) {
        ObjectNode form = json.createObjectNode().put("idToken", account).put("username", account)
                .put("role", "ROLE_" + role).put("consent", true);
        if (role.equals("VENUE") || role.equals("STUDIO")) {
            String prefix = role.toLowerCase(Locale.ROOT);
            form.put(prefix + "Name", "Fixture " + account).put(prefix + "Address", "Fixture street 15")
                    .put(role.equals("VENUE") ? "phone" : "studioPhone", "05550001122")
                    .put("cityId", cityId.toString()).put("districtId", districtId.toString())
                    .put("neighborhoodId", neighborhoodId.toString());
        }
        return form;
    }

    private void stubIdentity(String account) {
        when(googleIdentity.verify(account)).thenReturn(new VerifiedGoogleIdentity(account, email(account), null, true));
    }
    private static String email(String account) { return account + "@example.invalid"; }

    private void assertOnboarding(String account) throws Exception {
        JsonNode data = signIn(account, 200).path("data");
        assertThat(data.path("requiresGoogleProfileCompletion").asBoolean()).isTrue();
        assertThat(data.hasNonNull("token")).isFalse();
        assertThat(accounts(account)).isZero();
    }
    private int accounts(String account) {
        return jdbc.queryForObject("select count(*) from tbl_user where email=?", Integer.class, email(account));
    }
    private JsonNode signIn(String proof, int status) throws Exception {
        return request("POST", AUTH + "google-sign-in", null, Map.of("idToken", proof), status);
    }
    private JsonNode complete(Object body, int status) throws Exception {
        return request("POST", AUTH + "complete-google-profile", null, body, status);
    }
    private JsonNode passwordLogin(String username, int status) throws Exception {
        return request("POST", AUTH + "login", null, Map.of("username", username, "password", PASSWORD), status);
    }
    private JsonNode request(String method, String path, String bearer, Object body, int... expectedStatuses) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json");
        if (bearer != null) request.header("Authorization", "Bearer " + bearer);
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() :
                HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        var result = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode envelope = result.body().isBlank() ? json.createObjectNode() : json.readTree(result.body());
        assertThat(result.statusCode()).as("%s %s (app code %s)", method, path, envelope.path("code")).isIn(java.util.Arrays.stream(expectedStatuses).boxed().toList());
        if (method.equals("POST") && path.startsWith("/api/v1/admin/") && result.statusCode() == 200) {
            var requestId = UUID.fromString(result.headers().firstValue("X-Decision-Id").orElseThrow());
            var id = UUID.fromString(path.substring(path.lastIndexOf('/') + 1));
            String table = path.startsWith("/api/v1/admin/venue-applications/") ? "tbl_venue_applications" : "tbl_studio_applications";
            assertThat(jdbc.queryForObject("select decision_id from " + table + " where id=?", UUID.class, id)).isEqualTo(requestId);
        }
        return envelope;
    }
}
