package com.berkayb.soundconnect.modules.analytics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repeatable, closed-workload database check, deliberately separate from HTTP,
 * Redis quota, device rendering and production capacity tests. Run with
 * ./gradlew.bat venueAnalyticsLoadTest. No application config or real data used.
 */
@Tag("analytics-load")
class AnalyticsReportingLoadTest {
    private static final int WORKERS = 8;
    private static final int OPERATIONS_PER_WORKER = 350;
    private static final int DISTINCT_BATCHES = WORKERS * (OPERATIONS_PER_WORKER / 7);
    private static final int EVENTS = 10_000;
    private static final int FACTS_PER_EVENT = 12;
    private static final Instant NOW = Instant.parse("2026-09-08T12:00:00Z");
    private static final UUID OWNER = UUID.fromString("12a9972d-c160-4356-b17f-bd34a128f3ea");
    private static final UUID VENUE = UUID.fromString("82e8bcdd-4d8c-43d0-953f-9fbb78b15e66");
    private static final UUID HOT_EVENT = UUID.fromString("ea04d55c-acbb-4fd6-9522-065c557414ea");

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void boundedMixedReportingAndIngestionPreserveCountsUnderContention() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16.4-alpine")
                .withDatabaseName("venue_analytics_load_test")
                .withUsername("analytics_load_test").withPassword("test-only-load-password")
                .withReuse(false)) {
            postgres.start();
            var config = new HikariConfig();
            config.setJdbcUrl(postgres.getJdbcUrl());
            config.setUsername(postgres.getUsername());
            config.setPassword(postgres.getPassword());
            config.setMaximumPoolSize(WORKERS);
            config.setMinimumIdle(2);
            config.setConnectionTimeout(5_000);
            config.setPoolName("isolated-venue-analytics-load");
            try (var datasource = new HikariDataSource(config)) {
                // Validate exact, newly created container target before ANY schema write.
                try (var connection = datasource.getConnection()) {
                    assertThat(connection.getMetaData().getURL()).isEqualTo(postgres.getJdbcUrl());
                    assertThat(connection.getCatalog()).isEqualTo("venue_analytics_load_test");
                }
                var jdbc = new NamedParameterJdbcTemplate(datasource);
                schema(jdbc, datasource);
                seed(jdbc);
                var properties = new AnalyticsProperties();
                properties.setEnabled(true);
                properties.setReportingEnabled(true);
                properties.setHmacSecret("test-only-reporting-load-key-more-than-32-bytes");
                var store = new AnalyticsStore(jdbc, new DataSourceTransactionManager(datasource), new AnalyticsIdentity(properties));

                var before = store.summary(OWNER, VENUE, null, 30, NOW);
                assertThat(before.comparison().status()).isEqualTo(AnalyticsResponse.ComparisonStatus.AVAILABLE);
                assertThat(store.summary(OWNER, VENUE, HOT_EVENT, 90, NOW).metrics()).isEqualTo(AnalyticsResponse.Metrics.ZERO);

                var baseline = new ConcurrentLinkedQueue<Sample>();
                for (int round = 0; round < 3; round++) {
                    for (int days : List.of(7, 30, 90)) {
                        measure(baseline, "summary_" + days, () -> verifySummary(store, days));
                    }
                    measure(baseline, "metric_page", () -> verifyPage(store, roundSort(baseline.size()), 0));
                    measure(baseline, "event_90", () -> store.summary(OWNER, VENUE, HOT_EVENT, 90, NOW));
                    measure(baseline, "ordinary_event_lookup", () -> lookup(jdbc));
                }

                var samples = new ConcurrentLinkedQueue<Sample>();
                var ready = new CountDownLatch(WORKERS);
                var start = new CountDownLatch(1);
                var executor = Executors.newFixedThreadPool(WORKERS);
                var futures = new ArrayList<Future<?>>();
                long started = System.nanoTime();
                try {
                    for (int worker = 0; worker < WORKERS; worker++) {
                        final int index = worker;
                        futures.add(executor.submit(() -> {
                            ready.countDown();
                            try {
                                if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("Load start timed out");
                                for (int operation = 0; operation < OPERATIONS_PER_WORKER; operation++) {
                                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                                    final int sequence = operation;
                                    switch (operation % 7) {
                                        case 0 -> {
                                            int days = List.of(7, 30, 90).get((sequence / 7 + index) % 3);
                                            measure(samples, "summary_" + days, () -> verifySummary(store, days));
                                        }
                                        case 1 -> measure(samples, "metric_page", () -> verifyPage(store,
                                                roundSort(index + sequence), List.of(0, 250, 500).get((index + sequence) % 3)));
                                        case 2 -> measure(samples, "ingestion_and_retry", () -> {
                                            var request = request();
                                            store.observe(null, request, NOW);
                                            store.observe(null, request, NOW); // exact retry cannot inflate counts
                                        });
                                        case 3 -> measure(samples, "ordinary_event_lookup", () -> lookup(jdbc));
                                        case 4 -> measure(samples, "event_90", () -> {
                                            var result = store.summary(OWNER, VENUE, HOT_EVENT, 90, NOW);
                                            assertThat(result.daily()).hasSize(90);
                                            assertThat(result.metrics().impressions()).isBetween(0L, (long) DISTINCT_BATCHES);
                                        });
                                        case 5 -> measure(samples, "date_page", () -> verifyPage(store, AnalyticsResponse.EventSort.DATE, 0));
                                        case 6 -> measure(samples, "retention_cleanup", () -> store.cleanup(NOW));
                                        default -> throw new AssertionError();
                                    }
                                }
                                return null;
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(interrupted);
                            }
                        }));
                    }
                    assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                    started = System.nanoTime();
                    start.countDown();
                    final long deadline = started + TimeUnit.SECONDS.toNanos(120);
                    for (var future : futures) {
                        future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                    }
                } finally {
                    start.countDown();
                    for (var future : futures) if (!future.isDone()) future.cancel(true);
                    executor.shutdownNow();
                    assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
                }
                double elapsedMs = (System.nanoTime() - started) / 1_000_000.0;
                assertThat(samples).hasSize(WORKERS * OPERATIONS_PER_WORKER);
                var after = store.summary(OWNER, VENUE, null, 30, NOW);
                assertThat(after.metrics()).isEqualTo(new AnalyticsResponse.Metrics(
                        before.metrics().impressions() + DISTINCT_BATCHES,
                        before.metrics().detailViews() + DISTINCT_BATCHES,
                        before.metrics().profileVisits() + DISTINCT_BATCHES));
                // Today's traffic must not change comparisons of completed periods.
                assertThat(after.comparison()).isEqualTo(before.comparison());
                assertThat(store.summary(OWNER, VENUE, HOT_EVENT, 90, NOW).metrics())
                        .isEqualTo(new AnalyticsResponse.Metrics(DISTINCT_BATCHES, DISTINCT_BATCHES, DISTINCT_BATCHES));
                assertThat(jdbc.queryForObject("SELECT count(*) FROM tbl_venue_analytics_receipt", Map.of(), Long.class))
                        .isEqualTo(DISTINCT_BATCHES * 3L);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM tbl_venue_analytics_presence", Map.of(), Long.class))
                        .isEqualTo(EVENTS * (long) FACTS_PER_EVENT + DISTINCT_BATCHES * 3L);
                assertThat(store.retentionHealthy(NOW)).isTrue();

                var report = new LinkedHashMap<String, Object>();
                report.put("scope", "Isolated PostgreSQL store/transaction workload. NOT HTTP/device/Redis or production capacity.");
                report.put("model", "Closed workload: each of 8 workers waits for its operation to complete. Not sustained arrival-rate or soak proof.");
                report.put("seedEvents", EVENTS + 1);
                report.put("seedFacts", EVENTS * FACTS_PER_EVENT);
                report.put("expiredCleanupFacts", 2_000);
                report.put("workers", WORKERS);
                report.put("poolConnections", WORKERS);
                report.put("operations", samples.size());
                report.put("freshBatches", DISTINCT_BATCHES);
                report.put("identicalRetries", DISTINCT_BATCHES);
                report.put("elapsedMs", elapsedMs);
                report.put("operationsPerSecond", samples.size() * 1000 / elapsedMs);
                report.put("serialWarmBaselineMs", statistics(baseline));
                report.put("concurrentMs", statistics(samples));
                report.put("countAndRetryAssertions", "passed");
                Path directory = Path.of("build", "reports", "venue-analytics-load").toAbsolutePath().normalize();
                assertThat(directory.startsWith(Path.of("build").toAbsolutePath().normalize())).isTrue();
                Files.createDirectories(directory);
                Files.writeString(directory.resolve("report.json"), new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(report));
                // Broad local regression guard, not a deployment latency promise.
                for (var type : samples.stream().map(Sample::type).distinct().toList()) {
                    assertThat(percentile(samples.stream().filter(sample -> sample.type().equals(type)).toList(), .95))
                            .as("%s local p95 must remain below the 5s transaction budget", type).isLessThan(5_000);
                }
            }
        }
    }

    private static void schema(NamedParameterJdbcTemplate jdbc, HikariDataSource datasource) {
        jdbc.getJdbcTemplate().execute("CREATE TABLE tbl_user(id uuid PRIMARY KEY,status varchar(30),email_verified boolean)");
        jdbc.getJdbcTemplate().execute("CREATE TABLE tbl_role(id uuid PRIMARY KEY,name varchar(30)); CREATE TABLE user_roles(user_id uuid,role_id uuid)");
        jdbc.getJdbcTemplate().execute("CREATE TABLE tbl_venues(id uuid PRIMARY KEY,owner_id uuid REFERENCES tbl_user(id),status varchar(30))");
        jdbc.getJdbcTemplate().execute("CREATE TABLE tbl_event(id uuid PRIMARY KEY,venue_id uuid REFERENCES tbl_venues(id),title varchar(255),event_date date,start_time time,event_origin varchar(30),venue_calendar_approved boolean)");
        // Same event listing index shape as 2026-09-05-reciprocal-musician-events.sql.
        jdbc.getJdbcTemplate().execute("CREATE INDEX idx_event_published_venue_date ON tbl_event(venue_id,event_date,start_time,id) WHERE venue_calendar_approved");
        new ResourceDatabasePopulator(new FileSystemResource("scripts/db/2026-09-08-venue-analytics.sql")).execute(datasource);
    }

    private static void seed(NamedParameterJdbcTemplate jdbc) {
        var params = Map.<String, Object>of("owner", OWNER, "venue", VENUE, "hot", HOT_EVENT,
                "today", NOW.atZone(AnalyticsStore.ZONE).toLocalDate(), "events", EVENTS, "facts", FACTS_PER_EVENT);
        jdbc.update("INSERT INTO tbl_user VALUES (:owner,'ACTIVE',true)", params);
        jdbc.update("INSERT INTO tbl_venues VALUES (:venue,:owner,'APPROVED')", params);
        jdbc.update("""
                INSERT INTO tbl_event
                SELECT md5('load-event-'||n)::uuid,:venue,'Synthetic event '||n,
                       CAST(:today AS date) + (n % 60), '20:00'::time,'VENUE',true
                FROM generate_series(1,:events) n
                """, params);
        jdbc.update("INSERT INTO tbl_event VALUES (:hot,:venue,'Concurrent synthetic event',:today,'20:00','VENUE',true)", params);
        jdbc.update("""
                INSERT INTO tbl_venue_analytics_presence
                SELECT :venue,CAST(:today AS date) - ((event + viewer) % 90),
                       CASE viewer % 3 WHEN 0 THEN 'VENUE_PROFILE_VIEW' WHEN 1 THEN 'EVENT_IMPRESSION' ELSE 'EVENT_DETAIL_VIEW' END,
                       CASE WHEN viewer % 3 = 0 THEN '00000000-0000-0000-0000-000000000000'::uuid ELSE md5('load-event-'||event)::uuid END,
                       CASE WHEN viewer % 3 = 0 THEN md5('load-event-'||event)::uuid ELSE '00000000-0000-0000-0000-000000000000'::uuid END,
                       decode(md5('load-viewer-'||event||'-'||viewer)||md5('load-viewer-'||event||'-'||viewer),'hex')
                FROM generate_series(1,:events) event CROSS JOIN generate_series(1,:facts) viewer
                """, params);
        jdbc.update("""
                INSERT INTO tbl_venue_analytics_presence
                SELECT :venue,CAST(:today AS date)-100,'EVENT_IMPRESSION',:hot,
                       '00000000-0000-0000-0000-000000000000'::uuid,decode(md5('expired-'||n)||md5('expired-'||n),'hex')
                FROM generate_series(1,2000) n
                """, params);
        jdbc.update("UPDATE tbl_venue_analytics_state SET tracking_started_at=:started",
                Map.of("started", Timestamp.from(NOW.minus(Duration.ofDays(90)))));
        jdbc.getJdbcTemplate().execute("ANALYZE tbl_event; ANALYZE tbl_venue_analytics_presence");
    }

    private static AnalyticsRequest request() {
        return new AnalyticsRequest(UUID.randomUUID(), List.of(
                new AnalyticsRequest.Observation(UUID.randomUUID(), AnalyticsRequest.Type.EVENT_IMPRESSION, HOT_EVENT, null, null, NOW),
                new AnalyticsRequest.Observation(UUID.randomUUID(), AnalyticsRequest.Type.EVENT_DETAIL_VIEW, HOT_EVENT, null, null, NOW),
                new AnalyticsRequest.Observation(UUID.randomUUID(), AnalyticsRequest.Type.VENUE_PROFILE_VIEW, null, VENUE, HOT_EVENT, NOW)));
    }

    private static void verifySummary(AnalyticsStore store, int days) {
        var summary = store.summary(OWNER, VENUE, null, days, NOW);
        assertThat(summary.daily()).hasSize(days);
        assertThat(summary.comparison().status()).isEqualTo(days == 90
                ? AnalyticsResponse.ComparisonStatus.RETENTION_LIMIT : AnalyticsResponse.ComparisonStatus.AVAILABLE);
    }

    private static void verifyPage(AnalyticsStore store, AnalyticsResponse.EventSort sort, int page) {
        var result = store.events(OWNER, VENUE, 30, page, 20, sort, NOW);
        assertThat(result.totalElements()).isEqualTo(EVENTS + 1);
        assertThat(result.content()).hasSize(page == 500 ? 1 : 20);
        assertThat(result.sort()).isEqualTo(sort);
        if (sort != AnalyticsResponse.EventSort.DATE) {
            var values = result.content().stream().map(item -> switch (sort) {
                case REACH -> item.metrics().impressions();
                case DETAIL_VIEWS -> item.metrics().detailViews();
                case PROFILE_VISITS -> item.metrics().profileVisits();
                default -> throw new AssertionError();
            }).toList();
            assertThat(values).isSortedAccordingTo(Comparator.reverseOrder());
        }
    }

    private static AnalyticsResponse.EventSort roundSort(int value) {
        return List.of(AnalyticsResponse.EventSort.REACH, AnalyticsResponse.EventSort.DETAIL_VIEWS,
                AnalyticsResponse.EventSort.PROFILE_VISITS).get(value % 3);
    }

    private static void lookup(NamedParameterJdbcTemplate jdbc) {
        assertThat(jdbc.queryForList("SELECT id,title FROM tbl_event WHERE venue_id=:venue AND venue_calendar_approved ORDER BY event_date,start_time,id LIMIT 20",
                Map.of("venue", VENUE))).hasSize(20);
    }

    private static void measure(ConcurrentLinkedQueue<Sample> target, String type, Runnable work) {
        long start = System.nanoTime();
        work.run();
        target.add(new Sample(type, (System.nanoTime() - start) / 1_000_000.0));
    }

    private static Map<String, Object> statistics(ConcurrentLinkedQueue<Sample> samples) {
        var output = new LinkedHashMap<String, Object>();
        samples.stream().map(Sample::type).distinct().sorted().forEach(type -> {
            var values = samples.stream().filter(sample -> sample.type().equals(type)).toList();
            output.put(type, Map.of("samples", values.size(), "p50", percentile(values, .50),
                    "p95", percentile(values, .95), "p99", percentile(values, .99)));
        });
        return output;
    }

    private static double percentile(List<Sample> samples, double quantile) {
        var sorted = samples.stream().mapToDouble(Sample::milliseconds).sorted().toArray();
        return sorted[Math.max(0, (int) Math.ceil(sorted.length * quantile) - 1)];
    }

    private record Sample(String type, double milliseconds) { }
}
