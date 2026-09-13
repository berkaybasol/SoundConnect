package com.berkayb.soundconnect.modules.feed.musician.delivery;

import com.berkayb.soundconnect.modules.feed.musician.core.MusicianFeedProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@Timeout(30)
class MusicianFeedRetentionLookupPostgresTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("musician_feed_retention_lookup")
            .withUsername("soundconnect").withPassword("soundconnect");

    private static final Instant NOW = Instant.parse("2026-09-13T12:00:00Z");
    private static final int HISTORICAL_ROWS = 12_000;
    private static final String REPORTS = "tbl_musician_feed_content_report";
    private static final String FEEDBACK = "tbl_musician_feed_feedback";
    private final UUID viewer = UUID.randomUUID();
    private final UUID expiredDelivery = UUID.randomUUID();
    private final UUID liveDelivery = UUID.randomUUID();
    private JdbcTemplate sql;
    private MusicianFeedDeliveryCleanup cleanup;

    @BeforeEach
    void schema() throws Exception {
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        sql = new JdbcTemplate(source);
        sql.execute("drop schema public cascade; create schema public");
        sql.execute("create table tbl_user(id uuid primary key)");
        sql.update("insert into tbl_user(id) values (?)", viewer);
        sql.execute(migration("2026-09-11-musician-feed-delivery"));
        sql.execute(migration("2026-09-11-musician-feed-feedback"));
        cleanup = new MusicianFeedDeliveryCleanup(new NamedParameterJdbcTemplate(source),
                new MusicianFeedProperties(), Clock.fixed(NOW, ZoneOffset.UTC));

        // Permanent history outlives its delivery rows. The small live tail must
        // remain an indexed lookup regardless of the retained history size.
        sql.update("""
                insert into tbl_musician_feed_content_report(
                    id,viewer_user_id,delivery_id,item_id,item_type,target_type,target_id,reason,evidence_json,status,reported_at)
                select md5('historical-report-'||n)::uuid,?,null,'TRACK:history-'||n,'TRACK','MEDIA',
                    md5('historical-media-'||n)::uuid,'Historical report',
                    jsonb_build_object('payload',jsonb_build_object('title','Saved evidence '||n)),
                    'NEW',cast(? as timestamptz)
                from generate_series(1,?) n
                """, viewer, Timestamp.from(NOW.minusSeconds(86_400)), HISTORICAL_ROWS);
        sql.update("""
                insert into tbl_musician_feed_feedback(
                    id,viewer_user_id,action,scope_key,item_id,item_type,delivery_id,created_at,updated_at)
                select md5('historical-feedback-'||n)::uuid,?,'HIDE','ITEM:TRACK:history-'||n,
                    'TRACK:history-'||n,'TRACK',null,cast(? as timestamptz),cast(? as timestamptz)
                from generate_series(1,?) n
                """, viewer, Timestamp.from(NOW.minusSeconds(86_400)), Timestamp.from(NOW), HISTORICAL_ROWS);
        linkedDelivery(expiredDelivery, NOW.minusSeconds(1));
        linkedDelivery(liveDelivery, NOW.plusSeconds(86_400));
    }

    @Test
    void retentionUsesBothForeignKeyLookupIndexesAndPreservesDurableRows() throws Exception {
        String reportEvidenceBefore = durableFingerprint(REPORTS);
        String preferencesBefore = durableFingerprint(FEEDBACK);
        sql.execute(migration("2026-09-13-musician-feed-retention-lookup"));
        sql.execute("analyze tbl_musician_feed_content_report");
        sql.execute("analyze tbl_musician_feed_feedback");

        // Do not disable sequential scans: prove the planner chooses each
        // delivery-only lookup with ordinary PostgreSQL planner settings.
        assertIndexedLookup(REPORTS, "idx_musician_feed_report_delivery_lookup");
        assertIndexedLookup(FEEDBACK, "idx_musician_feed_feedback_delivery_lookup");
        assertThat(cleanup.purgeDeliveryBatch(10, NOW)).isEqualTo(1);

        for (String table : List.of(REPORTS, FEEDBACK)) {
            assertThat(sql.queryForObject("select count(*) from " + table, Long.class))
                    .isEqualTo(HISTORICAL_ROWS + 2L);
            assertThat(sql.queryForObject("select count(*) from " + table + " where delivery_id is null", Long.class))
                    .isEqualTo(HISTORICAL_ROWS + 1L);
            assertThat(sql.queryForObject("select count(*) from " + table + " where delivery_id=?",
                    Long.class, liveDelivery)).isEqualTo(1);
        }
        assertThat(durableFingerprint(REPORTS)).isEqualTo(reportEvidenceBefore);
        assertThat(durableFingerprint(FEEDBACK)).isEqualTo(preferencesBefore);
        assertThat(sql.queryForObject("select count(*) from tbl_musician_feed_delivery", Long.class)).isEqualTo(1);
    }

    @Test
    void migrationRetryPreservesReferencesAndRowsAfterRetention() throws Exception {
        String before = wholeFingerprint();
        sql.execute(migration("2026-09-13-musician-feed-retention-lookup"));
        sql.execute(migration("2026-09-13-musician-feed-retention-lookup"));
        assertThat(wholeFingerprint()).isEqualTo(before);
        assertThat(cleanup.purgeDeliveryBatch(10, NOW)).isEqualTo(1);
        String afterPurge = wholeFingerprint();
        sql.execute(migration("2026-09-13-musician-feed-retention-lookup"));
        assertThat(wholeFingerprint()).isEqualTo(afterPurge);
        assertThat(sql.queryForObject("""
                select count(*) from pg_indexes where schemaname='public' and indexname in
                    ('idx_musician_feed_report_delivery_lookup','idx_musician_feed_feedback_delivery_lookup')
                """, Long.class)).isEqualTo(2);
        assertThat(sql.queryForObject("""
                select count(*) from soundconnect_schema_migrations
                where migration_id='2026-09-13-musician-feed-retention-lookup'
                """, Long.class)).isEqualTo(1);
    }

    private void assertIndexedLookup(String table, String index) {
        String plan = String.join("\n", sql.queryForList(
                "explain (analyze,buffers) select id from " + table + " where delivery_id=?",
                String.class, expiredDelivery));
        assertThat(plan).contains(index).doesNotContain("Seq Scan");
    }

    private String durableFingerprint(String table) {
        return sql.queryForObject("select md5(string_agg((to_jsonb(retained)-'delivery_id')::text,'' order by id)) from "
                + table + " retained", String.class);
    }

    private String wholeFingerprint() {
        return sql.queryForObject("""
                select md5(string_agg(to_jsonb(retained)::text,'' order by id))
                from tbl_musician_feed_content_report retained
                """, String.class) + sql.queryForObject("""
                select md5(string_agg(to_jsonb(retained)::text,'' order by id))
                from tbl_musician_feed_feedback retained
                """, String.class);
    }

    private void linkedDelivery(UUID delivery, Instant purgeAfter) {
        sql.update("""
                insert into tbl_musician_feed_delivery(
                    id,viewer_user_id,feed_session_id,item_id,item_type,feed_lane,target_type,target_id,
                    feedback_capabilities,schema_version,algorithm_version,absolute_position,evidence_json,
                    delivered_at,expires_at,purge_after)
                values (?,?,?,?,'TRACK','FOLLOWING','MEDIA',?,'HIDE,REPORT',1,'test-v1',0,'{}',?,?,?)
                """, delivery, viewer, UUID.randomUUID(), "TRACK:" + delivery, UUID.randomUUID(),
                Timestamp.from(NOW.minusSeconds(100L * 86_400)), Timestamp.from(NOW.minusSeconds(99L * 86_400)),
                Timestamp.from(purgeAfter));
        sql.update("""
                insert into tbl_musician_feed_content_report(
                    id,viewer_user_id,delivery_id,item_id,item_type,target_type,target_id,reason,evidence_json,status,reported_at)
                values (?,?,?,?,'TRACK','MEDIA',?,'Keep this evidence',
                    '{"payload":{"title":"Immutable report evidence"}}','NEW',?)
                """, UUID.randomUUID(), viewer, delivery, "TRACK:" + delivery, UUID.randomUUID(), Timestamp.from(NOW));
        sql.update("""
                insert into tbl_musician_feed_feedback(
                    id,viewer_user_id,action,scope_key,item_id,item_type,delivery_id,created_at,updated_at)
                values (?,?,'REPORT',?,?,'TRACK',?,?,?)
                """, UUID.randomUUID(), viewer, "ITEM:TRACK:" + delivery, "TRACK:" + delivery,
                delivery, Timestamp.from(NOW), Timestamp.from(NOW));
    }

    private String migration(String name) throws Exception {
        return Files.readString(Path.of("scripts/db/" + name + ".sql"));
    }
}
