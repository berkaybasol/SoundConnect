package com.berkayb.soundconnect.modules.feed.musician.performance;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.berkayb.soundconnect.modules.event.support.EventShareUrlBuilder;
import com.berkayb.soundconnect.modules.feed.musician.api.*;
import com.berkayb.soundconnect.modules.feed.musician.candidate.*;
import com.berkayb.soundconnect.modules.feed.musician.core.*;
import com.berkayb.soundconnect.modules.feed.musician.cursor.MusicianFeedCursorCodec;
import com.berkayb.soundconnect.modules.feed.musician.delivery.*;
import com.berkayb.soundconnect.modules.feed.musician.feedback.*;
import com.berkayb.soundconnect.modules.feed.musician.mixer.MusicianFeedMixer;
import com.berkayb.soundconnect.modules.feed.musician.moderation.*;
import com.berkayb.soundconnect.modules.feed.musician.personalization.*;
import com.berkayb.soundconnect.modules.feed.musician.preference.service.MusicianFeedPreferencesService;
import com.berkayb.soundconnect.modules.feed.musician.provider.*;
import com.berkayb.soundconnect.modules.overthinking.service.OverthinkingPostService;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.repository.MusicianProfileRepository;
import com.berkayb.soundconnect.modules.profile.VenueProfile.repository.VenueProfileRepository;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.modules.venue.repository.VenueRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Service/JDBC development regression, NOT HTTP, Redis quota, media delivery or deployment capacity.
 * Actual Hibernate schema, production SQL providers, viewer repositories, feedback reader,
 * restriction guard, mixer, signed cursor, delivery transactions and replay visibility checks.
 * Musician preference storage uses fixed city/no-city snapshots. Venue city and ownership
 * and listener account cities use the production transactional personalization source.
 * Unused feedback write collaborators and the unadvertised Overthinking service are absent/mocked explicitly.
 */
@Tag("feed-load")
@DataJpaTest(showSql = false, properties = {
        "spring.config.location=classpath:/application-test.yml", "spring.config.import=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "spring.jpa.properties.hibernate.hbm2ddl.halt_on_error=true",
        "spring.datasource.hikari.maximum-pool-size=10", "spring.datasource.hikari.minimum-idle=2",
        "spring.datasource.hikari.connection-timeout=5000"
})
@ActiveProfiles("test")
@Testcontainers // Explicit task must fail, never silently skip, when Docker is unavailable.
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(MusicianFeedLoadTest.Wiring.class)
class MusicianFeedLoadTest {
    private static final int TRACKS = 50_000;
    private static final int EVENTS = 10_000;
    private static final int AUTHORS = 512;
    private static final int VIEWERS = 32;
    private static final int PAGE_SIZE = 20;
    private static final int PAGES_PER_WALK = 3;
    private static final int HISTORY_PER_VIEWER = 1_000;
    private static final List<String> TYPES = List.of("TRACK", "EVENT", "PROFILE");
    private static final UUID CITY = id("city");
    private static final BackstageFeedAudience AUDIENCE = BackstageFeedAudience.valueOf(
            System.getProperty("feedLoad.audience", "MUSICIAN").trim().toUpperCase(Locale.ROOT));
    private static final Path REPORT = Path.of("build", "reports",
            AUDIENCE.name().toLowerCase(Locale.ROOT) + "-feed-load", "report.json");
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("musician_feed_load_isolated")
            .withUsername("feed_load_test").withPassword("test-only-feed-load-password").withReuse(false);

    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired DataSource datasource;
    @Autowired NamedParameterJdbcTemplate jdbc;
    @Autowired MusicianFeedService service;
    @Autowired ObjectMapper mapper;
    @Autowired MusicianFeedProperties properties;
    @Autowired OverthinkingPostService unusedOverthinking;
    @Autowired ProviderAudit providers;
    @Autowired List<MusicianFeedCandidateProvider> diagnosticProviders;
    @Autowired MusicianFeedPersonalizationSource fixturePreferences;

    @Test
    @Timeout(value = 8, unit = TimeUnit.MINUTES)
    void largeFixturesAndConcurrentPageWalksKeepDeliveryAndReplayCorrect() throws Exception {
        var report = new LinkedHashMap<String, Object>();
        report.put("setupComplete", false);
        report.put("audience", AUDIENCE.name());
        report.put("scope", "Production service/JDBC boundary; real PostgreSQL schema/providers/guards/delivery/replay. No HTTP or Redis.");
        report.put("model", "Closed workload, each viewer awaits a page. Bounded development regression, not arrival-rate/soak/capacity proof.");
        report.put("fixture", Map.of("tracks", TRACKS, "futureEvents", EVENTS, "musicianAuthors", AUTHORS,
                "viewers", VIEWERS, "feedbackRows", VIEWERS * HISTORY_PER_VIEWER + VIEWERS,
                "historicalDeliveryRows", VIEWERS * HISTORY_PER_VIEWER, "qualifiedRecentViews", VIEWERS * 200, "likes", TRACKS * 2,
                "comments", TRACKS, "venueProfiles", 40 + (AUDIENCE == BackstageFeedAudience.VENUE ? VIEWERS : 0)));
        report.put("cityMatchingFixture", AUDIENCE == BackstageFeedAudience.LISTENER
                ? "Listener accounts use the fixture city for events; music discovery remains national."
                : AUDIENCE == BackstageFeedAudience.VENUE
                ? "All viewer venues use the fixture city; existing non-followed authors 128-255 match it, remaining authors have no city."
                : "Existing musician fixture: half of viewer snapshots use the fixture city, author city remains absent.");
        report.put("bounds", Map.of("providerWorkers", 6, "providerQueue", 24, "providerDeadlineMs", 4000,
                "jdbcPool", 10, "pageSize", PAGE_SIZE));
        report.put("provisionalDevelopmentGatesMs", Map.of("serialP95", 1000, "eightViewersP95", 1000,
                "sixteenViewersP95", 2000, "sameCursorRaceP95", 2000));
        report.put("notCovered", List.of("HTTP/authentication transport", "Redis quotas", "all ten provider families",
                "musician preference storage (fixed snapshots in MUSICIAN mode)", "media network/decoding",
                "production infrastructure", "long running soak"));
        report.put("personalization", AUDIENCE == BackstageFeedAudience.LISTENER
                ? "Production transactional listener profile/account city lookup, shared current content-audience checks."
                : AUDIENCE == BackstageFeedAudience.VENUE
                ? "Production transactional venue city/ownership lookup through real VenueRepository. No musician preferences read."
                : "Fixed city/no-city musician snapshots; musician preference storage is outside this measurement.");
        var serviceLogger = (Logger) LoggerFactory.getLogger(MusicianFeedService.class);
        var diagnostics = new ConcurrentLinkedQueue<String>();
        var appender = new AppenderBase<ILoggingEvent>() {
            @Override protected void append(ILoggingEvent event) {
                if (event.getLevel().isGreaterOrEqual(ch.qos.logback.classic.Level.WARN)) {
                    diagnostics.add(event.getFormattedMessage());
                }
            }
        };
        appender.start();
        serviceLogger.addAppender(appender);
        try {
            // DDL is directed solely by POSTGRES::getJdbcUrl; verify the exact newly created target
            // again before fixtures/migrations. No environment, saved connection or local DB accepted.
            try (var connection = datasource.getConnection()) {
                assertThat(connection.getMetaData().getURL()).isEqualTo(POSTGRES.getJdbcUrl());
                assertThat(connection.getCatalog()).isEqualTo("musician_feed_load_isolated");
            }
            assertThat(properties.getProviderParallelism()).isEqualTo(6);
            assertThat(properties.getProviderQueueCapacity()).isEqualTo(24);
            assertThat(properties.getProviderDeadline()).isEqualTo(java.time.Duration.ofSeconds(4));
            migrate();
            seed();
            report.put("setupComplete", true);
            report.put("diagnostics", diagnostics());
            if (Boolean.getBoolean("feedLoad.diagnosticsOnly")) {
                report.put("status", "diagnostic-only");
                return; // Explicitly not a passed load measurement; finally still writes the report.
            }
            // Warm PostgreSQL plans, JVM and serializers with the same real path, outside samples.
            walk(0, new ConcurrentLinkedQueue<>());
            walk(1, new ConcurrentLinkedQueue<>());
            report.put("serial", phase(1, 8));
            report.put("eightViewers", phase(8, 2));
            report.put("sixteenViewers", phase(16, 2));
            report.put("sameCursorRace", sameCursorRace());
            report.put("providerInvocations", providers.statistics());
            report.put("serviceWarnings", List.copyOf(diagnostics));
            assertThat(diagnostics).as("No optional-provider timeout/rejection/failure may masquerade as a fast partial feed").isEmpty();
            assertThat(providers.failures.sum()).isZero();
            assertThat(providers.started.sum()).isEqualTo(providers.completed.sum());
            verifyNoInteractions(unusedOverthinking);
            for (String phase : List.of("serial", "eightViewers", "sixteenViewers", "sameCursorRace")) {
                @SuppressWarnings("unchecked") var result = (Map<String, Object>) report.get(phase);
                double gate = phase.equals("serial") || phase.equals("eightViewers") ? 1000 : 2000;
                assertThat((double) result.get("p95Ms")).as("%s provisional development p95", phase).isLessThanOrEqualTo(gate);
            }
            report.put("status", "passed");
        } catch (Throwable failure) {
            report.put("status", "failed");
            report.put("failure", failure.toString());
            report.put("serviceWarnings", List.copyOf(diagnostics));
            report.put("providerInvocations", providers.statistics());
            throw failure;
        } finally {
            serviceLogger.detachAppender(appender);
            appender.stop();
            Path target = REPORT.toAbsolutePath().normalize();
            assertThat(target.startsWith(Path.of("build").toAbsolutePath().normalize())).isTrue();
            Files.createDirectories(target.getParent());
            Files.writeString(target, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        }
    }

    private Map<String, Object> phase(int workers, int walksPerWorker) throws Exception {
        var samples = new ConcurrentLinkedQueue<Double>();
        long sqlBefore = FeedLoadSqlCounter.EXECUTIONS.sum();
        long callsBefore = providers.completed.sum();
        long started = System.nanoTime();
        parallel(workers, worker -> {
            for (int walk = 0; walk < walksPerWorker; walk++) walk(worker, samples);
        });
        double elapsed = milliseconds(started);
        long statements = FeedLoadSqlCounter.EXECUTIONS.sum() - sqlBefore;
        int pages = workers * walksPerWorker * PAGES_PER_WALK;
        var result = statistics(samples, elapsed);
        result.put("viewers", workers);
        result.put("walksPerViewer", walksPerWorker);
        result.put("sqlExecutions", statements);
        result.put("sqlExecutionsPerPage", statements / (double) pages);
        assertThat(samples).hasSize(pages);
        assertThat(providers.completed.sum() - callsBefore).as("Every non-replay page must complete every advertised provider")
                .isEqualTo(pages * 3L);
        assertThat(statements / (double) pages).as("Bounded JDBC execute invocations per service page").isLessThan(100);
        return result;
    }

    private void walk(int viewerIndex, ConcurrentLinkedQueue<Double> samples) {
        UUID viewer = id("viewer-" + viewerIndex);
        Set<String> items = new HashSet<>();
        Set<String> targets = new HashSet<>();
        String cursor = null;
        UUID session = null;
        for (int pageNumber = 0; pageNumber < PAGES_PER_WALK; pageNumber++) {
            long started = System.nanoTime();
            var page = page(viewer, cursor);
            samples.add(milliseconds(started));
            assertThat(page.items().size()).as("page %s item count", pageNumber).isEqualTo(PAGE_SIZE);
            assertThat(page.hasMore()).isTrue();
            assertThat(page.nextCursor()).isNotBlank();
            assertThat(page.algorithmVersion()).isEqualTo(AUDIENCE.algorithmVersion());
            if (session == null) session = page.feedSessionId();
            assertThat(page.feedSessionId()).isEqualTo(session);
            for (int itemIndex = 0; itemIndex < page.items().size(); itemIndex++) {
                var item = page.items().get(itemIndex);
                assertThat(items.add(item.id())).as("No repeated item across page walk").isTrue();
                assertThat(targets.add(item.target().type() + ":" + item.target().id())).as("No repeated target across page walk").isTrue();
                assertThat(item.position()).isEqualTo((long) pageNumber * PAGE_SIZE + itemIndex);
                assertThat(item.impressionToken()).isNotBlank();
                assertThat(item.author().profileId()).isNotEqualTo(id("author-profile-0")); // persisted mute
                assertThat(item.author().userId()).isNotEqualTo(viewer);
            }
            cursor = page.nextCursor();
        }
        assertThat(jdbc.queryForObject("select count(*) from tbl_musician_feed_delivery where viewer_user_id=:viewer and feed_session_id=:session",
                Map.of("viewer", viewer, "session", session), Integer.class)).isEqualTo(PAGES_PER_WALK * PAGE_SIZE);
    }

    private Map<String, Object> sameCursorRace() throws Exception {
        UUID viewer = id("viewer-31");
        var first = page(viewer, null);
        var responses = new ConcurrentLinkedQueue<MusicianFeedPageResponse>();
        var samples = new ConcurrentLinkedQueue<Double>();
        long started = System.nanoTime();
        parallel(16, worker -> {
            long callStart = System.nanoTime();
            responses.add(page(viewer, first.nextCursor()));
            samples.add(milliseconds(callStart));
        });
        double elapsed = milliseconds(started);
        assertThat(responses.size()).isEqualTo(16);
        String canonical = mapper.writeValueAsString(responses.element());
        for (var response : responses) assertThat(mapper.writeValueAsString(response).equals(canonical))
                .as("Concurrent replay response must be byte identical").isTrue();
        assertThat(responses.element().items().size()).isEqualTo(PAGE_SIZE);
        assertThat(responses.element().items()).extracting(MusicianFeedItemResponse::id)
                .doesNotContainAnyElementsOf(first.items().stream().map(MusicianFeedItemResponse::id).toList());
        var parameters = Map.of("viewer", viewer, "session", first.feedSessionId());
        assertThat(jdbc.queryForObject("select count(*) from tbl_musician_feed_delivery where viewer_user_id=:viewer and feed_session_id=:session",
                parameters, Integer.class)).isEqualTo(PAGE_SIZE * 2);
        assertThat(jdbc.queryForObject("select count(*) from tbl_musician_feed_page_replay where viewer_user_id=:viewer and feed_session_id=:session",
                parameters, Integer.class)).isEqualTo(1);
        var result = statistics(samples, elapsed);
        result.put("byteIdenticalResponses", 16);
        result.put("ledgerItems", PAGE_SIZE * 2);
        result.put("replayRows", 1);
        return result;
    }

    private MusicianFeedPageResponse page(UUID viewer, String cursor) {
        return switch (AUDIENCE) {
            case MUSICIAN -> service.get(viewer, PAGE_SIZE, cursor, TYPES);
            case VENUE -> service.getForVenue(viewer, PAGE_SIZE, cursor, TYPES);
            case STUDIO -> service.getForStudio(viewer, PAGE_SIZE, cursor, TYPES);
            case LISTENER -> service.getForListener(viewer, PAGE_SIZE, cursor, TYPES);
        };
    }

    private static void parallel(int workers, Worker operation) throws Exception {
        var ready = new CountDownLatch(workers);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(workers);
        var futures = new ArrayList<Future<?>>();
        try {
            for (int worker = 0; worker < workers; worker++) {
                int index = worker;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("load start timed out");
                    operation.run(index);
                    return null;
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
            for (Future<?> future : futures) future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } finally {
            start.countDown();
            futures.forEach(future -> { if (!future.isDone()) future.cancel(true); });
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void migrate() throws Exception {
        // Transactional scripts contain DO blocks: send each entire script to PostgreSQL.
        for (String name : List.of("2026-09-11-musician-feed-delivery.sql", "2026-09-11-musician-feed-feedback.sql",
                "2026-09-11-musician-feed-replay.sql", "2026-09-13-musician-feed-feedback-lookup.sql",
                "2026-09-13-musician-feed-moderation.sql", "2026-09-13-musician-feed-retention-lookup.sql")) {
            jdbc.getJdbcTemplate().execute(Files.readString(Path.of("scripts", "db", name)));
        }
        // Online indexes explicitly require separate autocommit statements.
        String online = Files.readString(Path.of("scripts/db/2026-09-11-musician-feed-indexes.sql"))
                + "\n" + Files.readString(Path.of("scripts/db/2026-09-14-musician-feed-recent-views.sql"));
        String uncommented = online.lines().filter(line -> !line.stripLeading().startsWith("--"))
                .reduce("", (left, right) -> left + "\n" + right);
        try (var connection = datasource.getConnection(); var command = connection.createStatement()) {
            connection.setAutoCommit(true);
            for (String statement : uncommented.split(";")) if (!statement.isBlank()) command.execute(statement);
        }
    }

    private Map<String, Object> diagnostics() throws Exception {
        Path directory = REPORT.getParent().toAbsolutePath().normalize();
        assertThat(directory.startsWith(Path.of("build").toAbsolutePath().normalize())).isTrue();
        Files.createDirectories(directory);
        UUID viewer = id("viewer-0");
        UUID profile = id(AUDIENCE == BackstageFeedAudience.VENUE ? "viewer-venue-0" : "viewer-profile-0");
        UUID session = id("diagnostic-session");
        Instant anchor = Instant.now();
        var personalization = switch (AUDIENCE) {
            case MUSICIAN -> fixturePreferences.load(viewer, profile);
            case VENUE -> fixturePreferences.loadForVenue(viewer, profile);
            case STUDIO -> fixturePreferences.loadForStudio(viewer, profile);
            case LISTENER -> fixturePreferences.loadForListener(viewer, profile);
        };
        var request = new MusicianFeedCandidateRequest(viewer, profile, session, anchor, anchor,
                properties.getProviderLimit(), EnumSet.of(MusicianFeedItemType.TRACK, MusicianFeedItemType.EVENT,
                MusicianFeedItemType.PROFILE), personalization, MusicianFeedFeedbackSnapshot.empty()).withAudience(AUDIENCE);
        var latency = new LinkedHashMap<String, Object>();
        for (MusicianFeedCandidateProvider provider : diagnosticProviders) {
            var samples = new ArrayList<Double>();
            var candidateCounts = new ArrayList<Integer>();
            long started = System.nanoTime();
            for (int round = 0; round < 3; round++) {
                long callStart = System.nanoTime();
                var result = provider.findCandidates(request);
                samples.add(milliseconds(callStart));
                candidateCounts.add(result.size());
            }
            var measured = statistics(samples, milliseconds(started));
            measured.put("candidateCounts", candidateCounts);
            latency.put(provider.providerId(), measured);
        }
        Files.writeString(directory.resolve("provider-diagnostics.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(latency));

        // Reflect the exact current production statement; never maintain a simpler substitute query.
        var field = MusicianFeedTrackCandidateProvider.class.getDeclaredField("SQL");
        field.setAccessible(true);
        String productionSql = (String) field.get(null);
        var planJdbc = new NamedParameterJdbcTemplate(datasource);
        planJdbc.getJdbcTemplate().setQueryTimeout(15);
        var parameters = new HashMap<String, Object>();
        parameters.put("viewerId", viewer); parameters.put("feedSessionId", session);
        parameters.put("anchor", Timestamp.from(anchor));
        parameters.put("venueAudience", AUDIENCE == BackstageFeedAudience.VENUE);
        parameters.put("listenerAudience", AUDIENCE == BackstageFeedAudience.LISTENER);
        parameters.put("hasCity", AUDIENCE == BackstageFeedAudience.VENUE);
        parameters.put("cityId", AUDIENCE == BackstageFeedAudience.VENUE ? CITY : new UUID(0, 0));
        parameters.put("artistCityPool", "ALL");
        var plans = new ArrayList<String>();
        for (int delivered : List.of(0, 60)) {
            if (delivered > 0) {
                jdbc.update("""
                        insert into tbl_musician_feed_delivery(id,viewer_user_id,feed_session_id,item_id,item_type,feed_lane,
                            target_type,target_id,feedback_capabilities,schema_version,algorithm_version,absolute_position,
                            evidence_json,delivered_at,expires_at,purge_after)
                        select md5('diagnostic-delivery-'||n)::uuid,:viewerId,:feedSessionId,'TRACK:'||md5('track-'||n)::uuid::text,
                            'TRACK','FOLLOWING','MEDIA',md5('media-'||n)::uuid,'HIDE',1,'fixture',n-1,'{}'::jsonb,
                            cast(:anchor as timestamptz),cast(:anchor as timestamptz)+interval '1 day',
                            cast(:anchor as timestamptz)+interval '90 days' from generate_series(1,60) n
                        """, parameters);
            }
            for (boolean following : List.of(true, false)) {
                parameters.put("followingPool", following);
                parameters.put("limit", AUDIENCE == BackstageFeedAudience.VENUE ? 80 : following ? 120 : 40);
                List<String> cityPools = AUDIENCE == BackstageFeedAudience.VENUE && !following
                        ? List.of("LOCAL", "OTHER") : List.of("ALL");
                for (String cityPool : cityPools) {
                    parameters.put("artistCityPool", cityPool);
                    String name = "track-plan-" + (following ? "following" : "discovery")
                            + (cityPool.equals("ALL") ? "" : "-" + cityPool.toLowerCase(Locale.ROOT))
                            + "-delivered-" + delivered + ".json";
                    String plan = planJdbc.queryForObject("EXPLAIN (ANALYZE,BUFFERS,SETTINGS,FORMAT JSON) " + productionSql,
                            parameters, String.class);
                    Files.writeString(directory.resolve(name), plan);
                    plans.add(name);
                }
            }
        }
        var eventField = MusicianFeedEventCandidateProvider.class.getDeclaredField("SQL");
        eventField.setAccessible(true);
        String eventSql = (String) eventField.get(null);
        var eventPlans = new ArrayList<String>();
        var localNow = anchor.atZone(java.time.ZoneId.of("Europe/Istanbul"));
        parameters.put("today", localNow.toLocalDate());
        parameters.put("nowSeconds", localNow.toLocalTime().toSecondOfDay());
        parameters.put("storageMidnight", Instant.ofEpochMilli(java.sql.Time.valueOf(java.time.LocalTime.MIDNIGHT).getTime())
                .atZone(ZoneOffset.UTC).toLocalTime());
        for (boolean hasCity : List.of(true, false)) {
            parameters.put("hasCity", hasCity);
            parameters.put("cityId", hasCity ? CITY : new UUID(0, 0));
            for (String pool : List.of("FOLLOWING", "RELEVANT", "GENERAL")) {
                parameters.put("pool", pool);
                parameters.put("limit", pool.equals("FOLLOWING") ? 96 : pool.equals("RELEVANT") ? 48 : 16);
                String name = "event-plan-" + pool.toLowerCase(Locale.ROOT) + "-city-" + hasCity + ".json";
                String plan = planJdbc.queryForObject("EXPLAIN (ANALYZE,BUFFERS,SETTINGS,FORMAT JSON) " + eventSql,
                        parameters, String.class);
                Files.writeString(directory.resolve(name), plan);
                eventPlans.add(name);
            }
        }
        return Map.of("audience", AUDIENCE.name(), "providerLatency", latency, "trackPlanFiles", plans, "eventPlanFiles", eventPlans, "diagnosticDeliveryRows", 60,
                "scope", "Direct production-provider calls and exact SQL plans before timed workload; no planner settings changed.");
    }

    private void seed() {
        Instant now = Instant.now().minusSeconds(60);
        var parameters = new HashMap<String, Object>();
        parameters.put("now", Timestamp.from(now));
        parameters.put("tomorrow", LocalDate.ofInstant(now, ZoneOffset.UTC).plusDays(1));
        parameters.put("authors", AUTHORS); parameters.put("viewers", VIEWERS);
        parameters.put("tracks", TRACKS); parameters.put("events", EVENTS);
        parameters.put("history", HISTORY_PER_VIEWER); parameters.put("city", CITY);
        parameters.put("venueAudience", AUDIENCE == BackstageFeedAudience.VENUE);
        parameters.put("listenerAudience", AUDIENCE == BackstageFeedAudience.LISTENER);
        jdbc.update("""
                insert into tbl_user(id,created_at,updated_at,public_code,user_name,password,email,status,provider,email_verified)
                select md5(kind||'-'||n)::uuid,:now,:now,'SC-'||substr(md5(kind||'-'||n),1,20),
                    kind||'-'||n,'not-used',kind||'-'||n||'@soundconnect.test','ACTIVE','LOCAL',true
                from (select 'author' kind,generate_series(0,:authors-1) n union all
                      select 'viewer',generate_series(0,:viewers-1)) people
                """, parameters);
        jdbc.update("""
                insert into tbl_musician_profile(id,created_at,updated_at,user_id,name,stage_name)
                select md5(kind||'-profile-'||n)::uuid,:now,:now,md5(kind||'-'||n)::uuid,kind||'-'||n,kind||'-'||n
                from (select 'author' kind,generate_series(0,:authors-1) n union all
                      select 'viewer',generate_series(0,:viewers-1)) people
                where kind='author' or (not :venueAudience and not :listenerAudience)
                """, parameters);
        jdbc.update("insert into tbl_role(id,created_at,updated_at,name) values(md5('musician-role')::uuid,:now,:now,'ROLE_MUSICIAN')", parameters);
        if (AUDIENCE == BackstageFeedAudience.VENUE) {
            jdbc.update("insert into tbl_role(id,created_at,updated_at,name) values(md5('venue-role')::uuid,:now,:now,'ROLE_VENUE')", parameters);
        }
        if (AUDIENCE == BackstageFeedAudience.LISTENER) {
            jdbc.update("insert into tbl_role(id,created_at,updated_at,name) values(md5('listener-role')::uuid,:now,:now,'ROLE_LISTENER')", parameters);
            jdbc.update("""
                    insert into "tbl_listener-profile"(id,created_at,updated_at,user_id,name,visibility_mode,
                        visibility_choice_completed,version,playlist_revision)
                    select md5('viewer-profile-'||n)::uuid,:now,:now,md5('viewer-'||n)::uuid,'Viewer '||n,
                        'STANDARD',true,0,0 from generate_series(0,:viewers-1) n
                    """, parameters);
        }
        jdbc.update("""
                insert into user_roles(user_id,role_id)
                select id,case when :venueAudience and user_name like 'viewer-%' then md5('venue-role')::uuid
                    when :listenerAudience and user_name like 'viewer-%' then md5('listener-role')::uuid
                    else md5('musician-role')::uuid end from tbl_user
                """, parameters);
        jdbc.update("""
                insert into tbl_follow(id,created_at,updated_at,follower_id,following_id,followed_at)
                select md5('follow-'||v||'-'||a)::uuid,:now,:now,md5('viewer-'||v)::uuid,md5('author-'||a)::uuid,:now
                from generate_series(0,:viewers-1) v cross join generate_series(0,127) a
                """, parameters);
        jdbc.update("""
                insert into tbl_media_asset(id,created_at,updated_at,kind,status,visibility,owner_type,owner_id,
                    source_url,playback_url,mime_type,size,streaming_protocol,transcode_attempt_count,
                    transcode_retry_pending,transcode_retain_source_after_cleanup)
                select md5('media-'||n)::uuid,cast(:now as timestamp)-n*interval '1 second',:now,'AUDIO','READY','PUBLIC','MUSICIAN_PROFILE',
                    md5('author-profile-'||(n%:authors))::uuid,'https://cdn.test/source.mp3','https://cdn.test/play.mp3',
                    'audio/mpeg',1234,'PROGRESSIVE',0,false,false from generate_series(1,:tracks) n
                """, parameters);
        jdbc.update("""
                insert into tbl_tracks(id,created_at,updated_at,media_asset_id,owner_type,owner_id,title)
                select md5('track-'||n)::uuid,cast(:now as timestamp)-n*interval '1 second',:now,md5('media-'||n)::uuid,
                    'MUSICIAN_PROFILE',md5('author-profile-'||(n%:authors))::uuid,'Synthetic track '||n
                from generate_series(1,:tracks) n
                """, parameters);
        jdbc.update("""
                insert into tbl_like(id,created_at,updated_at,user_id,target_type,target_id)
                select md5('like-'||n||'-'||actor)::uuid,:now,:now,md5('author-'||actor)::uuid,'MEDIA',md5('media-'||n)::uuid
                from generate_series(1,:tracks) n cross join generate_series(500,501) actor
                """, parameters);
        jdbc.update("""
                insert into tbl_comment(id,created_at,updated_at,user_id,target_type,target_id,text,is_deleted)
                select md5('comment-'||n)::uuid,:now,:now,md5('author-502')::uuid,'MEDIA',md5('media-'||n)::uuid,'Fixture comment',false
                from generate_series(1,:tracks) n
                """, parameters);
        jdbc.update("insert into tbl_city(id,created_at,updated_at,name) values(:city,:now,:now,'Fixture city')", parameters);
        if (AUDIENCE == BackstageFeedAudience.LISTENER) {
            jdbc.update("update tbl_user set city_id=:city where user_name like 'viewer-%'", parameters);
        }
        jdbc.update("insert into tbl_district(id,created_at,updated_at,name,city_id) values(md5('district')::uuid,:now,:now,'Fixture district',:city)", parameters);
        jdbc.update("insert into tbl_neighborhood(id,created_at,updated_at,name,district_id) values(md5('neighborhood')::uuid,:now,:now,'Fixture neighborhood',md5('district')::uuid)", parameters);
        if (AUDIENCE == BackstageFeedAudience.VENUE) {
            // Reuse the same isolated authors and location chain; exercise local and fallback
            // discovery without changing the default musician workload or duplicating fixtures.
            jdbc.update("""
                    update tbl_user set city_id=:city
                    where id in (select md5('author-'||n)::uuid from generate_series(128,255) n)
                    """, parameters);
            jdbc.update("""
                    insert into tbl_venues(id,created_at,updated_at,name,address,city_id,district_id,neighborhood_id,status,owner_id)
                    select md5('viewer-venue-'||n)::uuid,:now,:now,'Viewer venue '||n,'Fixture address',:city,
                        md5('district')::uuid,md5('neighborhood')::uuid,'APPROVED',md5('viewer-'||n)::uuid
                    from generate_series(0,:viewers-1) n
                    """, parameters);
            jdbc.update("""
                    insert into tbl_venue_profile(id,created_at,updated_at,venue_id,bio)
                    select md5('viewer-venue-profile-'||n)::uuid,:now,:now,md5('viewer-venue-'||n)::uuid,
                        'Synthetic viewer venue profile' from generate_series(0,:viewers-1) n
                    """, parameters);
        }
        jdbc.update("""
                insert into tbl_venues(id,created_at,updated_at,name,address,city_id,district_id,neighborhood_id,status,owner_id)
                select md5('venue-'||n)::uuid,:now,:now,'Fixture venue '||n,'Fixture address',:city,md5('district')::uuid,
                    md5('neighborhood')::uuid,'APPROVED',md5('author-'||(n+200))::uuid from generate_series(0,39) n
                """, parameters);
        jdbc.update("""
                insert into tbl_venue_profile(id,created_at,updated_at,venue_id,bio)
                select md5('venue-profile-'||n)::uuid,:now,:now,md5('venue-'||n)::uuid,'Synthetic venue profile'
                from generate_series(0,39) n
                """, parameters);
        jdbc.update("""
                insert into tbl_event(id,created_at,updated_at,title,event_date,start_time,venue_id,event_origin,
                    organizer_user_id,venue_approval_status,venue_calendar_approved,performer_approval_status,
                    profile_calendar_approved,profile_publication_version)
                select md5('event-'||n)::uuid,:now,:now,'Fixture event '||n,cast(:tomorrow as date)+(n%30),'20:00'::time,
                    md5('venue-'||(n%40))::uuid,'VENUE',md5('author-'||(n%40+200))::uuid,'APPROVED',true,'NOT_REQUIRED',false,0
                from generate_series(1,:events) n
                """, parameters);
        jdbc.update("""
                insert into tbl_musician_feed_feedback(id,viewer_user_id,action,scope_key,item_id,item_type,created_at,updated_at)
                select md5('feedback-'||v||'-'||n)::uuid,md5('viewer-'||v)::uuid,
                    case when n%2=0 then 'SHOW_LESS' else 'HIDE' end,'ITEM:TRACK:historical-'||n,'TRACK:historical-'||n,
                    'TRACK',cast(:now as timestamptz)-interval '30 days',cast(:now as timestamptz)-interval '30 days'
                from generate_series(0,:viewers-1) v cross join generate_series(1,:history) n
                """, parameters);
        jdbc.update("""
                insert into tbl_musician_feed_feedback(id,viewer_user_id,action,scope_key,author_profile_type,author_profile_id,created_at,updated_at)
                select md5('mute-'||v)::uuid,md5('viewer-'||v)::uuid,'MUTE_AUTHOR','AUTHOR:MUSICIAN:'||md5('author-profile-0')::uuid::text,
                    'MUSICIAN',md5('author-profile-0')::uuid,:now,:now from generate_series(0,:viewers-1) v
                """, parameters);
        jdbc.update("""
                insert into tbl_musician_feed_delivery(id,viewer_user_id,feed_session_id,item_id,item_type,feed_lane,target_type,target_id,
                    feedback_capabilities,schema_version,algorithm_version,absolute_position,evidence_json,delivered_at,expires_at,purge_after)
                select md5('old-delivery-'||v||'-'||n)::uuid,md5('viewer-'||v)::uuid,md5('old-session-'||v)::uuid,
                    'TRACK:historical-'||n,'TRACK','FOLLOWING','MEDIA',md5('old-media-'||n)::uuid,'HIDE',1,'fixture',n-1,
                    '{}'::jsonb,cast(:now as timestamptz)-interval '2 days',cast(:now as timestamptz)-interval '1 day',
                    cast(:now as timestamptz)+interval '88 days'
                from generate_series(0,:viewers-1) v cross join generate_series(1,:history) n
                """, parameters);
        // Keep actual recent-view history in the isolated load fixture. The source
        // rows and telemetry exist only in the explicitly asserted Testcontainer.
        jdbc.update("""
                update tbl_musician_feed_delivery
                set target_id=md5('media-'||(absolute_position+21))::uuid,
                    delivered_at=cast(:now as timestamptz)-interval '2 seconds',
                    expires_at=cast(:now as timestamptz)+interval '1 day'
                where algorithm_version='fixture' and absolute_position<200
                """, parameters);
        jdbc.update("""
                insert into tbl_musician_feed_telemetry_event(id,viewer_user_id,client_event_id,delivery_id,
                    event_type,client_occurred_at,recorded_at)
                select md5('impression-'||id)::uuid,viewer_user_id,md5('impression-'||id)::uuid,id,
                    'IMPRESSION',:now,:now from tbl_musician_feed_delivery
                where algorithm_version='fixture' and absolute_position<200
                """, parameters);
        jdbc.getJdbcTemplate().execute("ANALYZE");
        assertThat(jdbc.queryForObject("select count(*) from tbl_tracks", Map.of(), Integer.class)).isEqualTo(TRACKS);
    }

    private static UUID id(String value) {
        try { return UUID.fromString(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("MD5")
                .digest(value.getBytes(StandardCharsets.UTF_8))).replaceFirst("(.{8})(.{4})(.{4})(.{4})(.{12})", "$1-$2-$3-$4-$5")); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static double milliseconds(long start) { return (System.nanoTime() - start) / 1_000_000.0; }
    private static Map<String, Object> statistics(Collection<Double> samples, double elapsed) {
        double[] sorted = samples.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        var result = new LinkedHashMap<String, Object>();
        result.put("samples", sorted.length); result.put("elapsedMs", elapsed);
        result.put("requestsPerSecond", sorted.length * 1000 / elapsed);
        result.put("p50Ms", sorted[(int) Math.ceil(sorted.length * .50) - 1]);
        result.put("p95Ms", sorted[(int) Math.ceil(sorted.length * .95) - 1]);
        result.put("maxMs", sorted[sorted.length - 1]); result.put("unexpectedErrors", 0);
        return result;
    }
    @FunctionalInterface private interface Worker { void run(int index) throws Exception; }

    static final class ProviderAudit {
        final LongAdder started = new LongAdder(), completed = new LongAdder(), failures = new LongAdder();
        final Map<String, LongAdder> rows = new ConcurrentHashMap<>();
        MusicianFeedCandidateProvider wrap(MusicianFeedCandidateProvider delegate) {
            return new MusicianFeedCandidateProvider() {
                @Override public String providerId() { return delegate.providerId(); }
                @Override public Set<MusicianFeedItemType> supportedTypes() { return delegate.supportedTypes(); }
                @Override public boolean optional() { return delegate.optional(); }
                @Override public List<MusicianFeedCandidate> findCandidates(MusicianFeedCandidateRequest request) {
                    started.increment();
                    try {
                        var result = delegate.findCandidates(request);
                        assertThat(result.size()).as("Seeded provider must return candidates: %s", providerId()).isPositive();
                        assertThat(result.size()).isLessThanOrEqualTo(request.limit());
                        rows.computeIfAbsent(providerId(), ignored -> new LongAdder()).add(result.size());
                        completed.increment();
                        return result;
                    } catch (Throwable failure) { failures.increment(); throw failure; }
                }
            };
        }
        Map<String, Object> statistics() {
            var counts = new TreeMap<String, Long>(); rows.forEach((key, value) -> counts.put(key, value.sum()));
            return Map.of("started", started.sum(), "completed", completed.sum(), "failed", failures.sum(), "candidateRows", counts);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @Import({MusicianFeedConfiguration.class, MusicianFeedTrackCandidateProvider.class,
            MusicianFeedEventCandidateProvider.class, MusicianFeedProfileDiscoveryCandidateProvider.class,
            MusicianFeedDeliveryService.class, MusicianFeedFeedbackReader.class,
            com.berkayb.soundconnect.modules.feed.listener.core.ListenerFeedContentPolicy.class,
            com.berkayb.soundconnect.modules.feed.musician.delivery.MusicianFeedDeliveryLookup.class})
    static class Wiring {
        @Bean static BeanPostProcessor countDataSource() {
            return new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    return bean instanceof DataSource data && !(bean instanceof FeedLoadSqlCounter)
                            ? new FeedLoadSqlCounter(data) : bean;
                }
            };
        }
        @Bean ObjectMapper mapper() { return new ObjectMapper().findAndRegisterModules(); }
        @Bean EventShareUrlBuilder shareUrls() { return new EventShareUrlBuilder("https://soundconnect.test"); }
        @Bean MusicianFeedDeliveryTokenCodec tokens(ObjectMapper mapper, MusicianFeedProperties properties) {
            return new MusicianFeedDeliveryTokenCodec(mapper, properties);
        }
        @Bean MusicianFeedViewerGuard viewer(UserRepository users, MusicianProfileRepository musicians,
                                            VenueRepository venues, VenueProfileRepository venueProfiles,
                                            com.berkayb.soundconnect.modules.profile.ListenerProfile.repository.ListenerProfileRepository listeners) {
            return new MusicianFeedViewerGuard(users, musicians, venues, venueProfiles, listeners);
        }
        @Bean MusicianFeedRestrictionGuard restrictions(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
            return new MusicianFeedRestrictionGuard(new MusicianFeedRestrictionRepository(jdbc), new MusicianFeedModerationScopeResolver(mapper));
        }
        @Bean OverthinkingPostService unusedOverthinking() { return mock(OverthinkingPostService.class); }
        @Bean MusicianFeedReplayVisibilityGuard visibility(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper,
                OverthinkingPostService overthinking, MusicianFeedRestrictionGuard restrictions) {
            return new MusicianFeedReplayVisibilityGuard(jdbc, mapper, overthinking, restrictions);
        }
        @Bean MusicianFeedFeedbackService feedback(MusicianFeedFeedbackReader reader, MusicianFeedViewerGuard viewer) {
            // No write operation is called. Read production code uses only the real JDBC reader.
            return new MusicianFeedFeedbackService(null, viewer, null, null, null, null, reader);
        }
        @Bean MusicianFeedPersonalizationSource venuePreferenceStorage(VenueRepository venues,
                com.berkayb.soundconnect.modules.profile.ListenerProfile.repository.ListenerProfileRepository listeners) {
            // Spring proxies the production method's @Transactional boundary. The musician
            // collaborator is never used in this audience; no parallel fixed venue snapshot.
            return new PreferenceBackedMusicianFeedPersonalizationSource(mock(MusicianFeedPreferencesService.class), venues, listeners);
        }
        @Bean @Primary MusicianFeedPersonalizationSource fixturePreferences(
                @Qualifier("venuePreferenceStorage") MusicianFeedPersonalizationSource venuePreferences) {
            return new MusicianFeedPersonalizationSource() {
                @Override public MusicianFeedPersonalizationSnapshot load(UUID viewer, UUID profile) {
                    return new MusicianFeedPersonalizationSnapshot(
                            (viewer.getLeastSignificantBits() & 1) == 0 ? CITY : null, Set.of(), null);
                }
                @Override public MusicianFeedPersonalizationSnapshot loadForVenue(UUID viewer, UUID venue) {
                    return venuePreferences.loadForVenue(viewer, venue);
                }
                @Override public MusicianFeedPersonalizationSnapshot loadForListener(UUID viewer, UUID listener) {
                    return venuePreferences.loadForListener(viewer, listener);
                }
            };
        }
        @Bean ProviderAudit audit() { return new ProviderAudit(); }
        @Bean MusicianFeedService feed(MusicianFeedProperties properties, MusicianFeedViewerGuard viewer,
                MusicianFeedFeedbackService feedback, MusicianFeedRestrictionGuard restrictions,
                MusicianFeedPersonalizationSource preferences, MusicianFeedDeliveryService deliveries,
                List<MusicianFeedCandidateProvider> candidates, @Qualifier("musicianFeedProviderExecutor") ExecutorService executor,
                ObjectMapper mapper, ProviderAudit audit) {
            return new MusicianFeedService(properties, viewer, new MusicianFeedCursorCodec(mapper, properties),
                    new MusicianFeedMixer(), feedback, restrictions, preferences, deliveries,
                    candidates.stream().map(audit::wrap).toList(), List.of(), executor);
        }
    }
}
