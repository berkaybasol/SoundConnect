package com.berkayb.soundconnect.modules.feed.musician.moderation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

@Testcontainers(disabledWithoutDocker = true)
@Timeout(30)
class MusicianFeedOrphanRestrictionLookupPostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("musician_orphan_lookup").withUsername("soundconnect").withPassword("soundconnect");
    private static final Instant NOW = Instant.parse("2026-09-13T15:30:00Z");
    private final UUID reporter = UUID.randomUUID();
    private final UUID actor = UUID.randomUUID();
    private JdbcTemplate sql;
    private NamedParameterJdbcTemplate jdbc;
    private MusicianFeedOrphanRestrictionRepository repository;
    private MusicianFeedRestrictionRepository restrictions;

    @BeforeEach
    void setUp() throws Exception {
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        sql = new JdbcTemplate(source);
        jdbc = spy(new NamedParameterJdbcTemplate(source));
        repository = new MusicianFeedOrphanRestrictionRepository(jdbc);
        restrictions = new MusicianFeedRestrictionRepository(jdbc);
        sql.execute("drop schema public cascade; create schema public");
        sql.execute("create table tbl_user(id uuid primary key)");
        sql.update("insert into tbl_user(id) values (?),(?)", reporter, actor);
        sql.execute(Files.readString(Path.of("scripts/db/2026-09-11-musician-feed-delivery.sql")));
        sql.execute(Files.readString(Path.of("scripts/db/2026-09-13-musician-feed-moderation.sql")));
    }

    @Test
    void queueUsesThePartialOrphanIndexInsteadOfScanningThousandsOfNormalRestrictions() throws Exception {
        sql.update("""
                insert into tbl_musician_feed_content_report(id,viewer_user_id,item_id,item_type,
                    target_type,target_id,evidence_json,status,reported_at)
                select md5('normal-report-'||n)::uuid,?,'TRACK:'||md5('normal-track-'||n)::uuid,'TRACK',
                    'MEDIA',md5('normal-media-'||n)::uuid,'{}'::jsonb,'ACTIONED',?
                from generate_series(1,6000) n
                """, reporter, Timestamp.from(NOW.minusSeconds(30)));
        sql.update("""
                insert into tbl_musician_feed_restriction(report_id,scope_key,active,orphaned,
                    applied_by_user_id,applied_at,updated_at)
                select id,'TARGET:MEDIA:'||target_id,true,false,?,?,?
                from tbl_musician_feed_content_report
                """, actor, Timestamp.from(NOW.minusSeconds(30)), Timestamp.from(NOW.minusSeconds(30)));
        UUID newerOrphan = UUID.randomUUID();
        UUID olderOrphan = UUID.randomUUID();
        restrictions.apply(newerOrphan, "TARGET:MEDIA:" + UUID.randomUUID(), actor, NOW.minusSeconds(60));
        restrictions.apply(olderOrphan, "TARGET:MEDIA:" + UUID.randomUUID(), actor, NOW.minusSeconds(120));
        sql.execute("analyze tbl_musician_feed_content_report");
        sql.execute("analyze tbl_musician_feed_restriction");

        // The normal restrictions are newer: an unfiltered time index would have
        // to visit all 6,000 before finding either orphan on this first page.
        clearInvocations(jdbc);
        assertThat(repository.page(NOW, null, 51)).extracting(MusicianFeedOrphanRestrictionRepository.Row::reportId)
                .containsExactly(newerOrphan, olderOrphan);
        assertBoundedCapturedPagePlan();

        clearInvocations(jdbc);
        var position = new MusicianFeedOrphanRestrictionCursor.Position(NOW, NOW.minusSeconds(60), newerOrphan);
        assertThat(repository.page(NOW, position, 51)).extracting(MusicianFeedOrphanRestrictionRepository.Row::reportId)
                .containsExactly(olderOrphan);
        assertBoundedCapturedPagePlan();
    }

    @SuppressWarnings("unchecked")
    private void assertBoundedCapturedPagePlan() throws Exception {
        var statement = ArgumentCaptor.forClass(String.class);
        var parameters = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).query(statement.capture(), parameters.capture(), any(RowMapper.class));
        JsonNode plan = new ObjectMapper().readTree(jdbc.queryForObject(
                "explain (analyze,format json) " + statement.getValue(), parameters.getValue(), String.class))
                .get(0).get("Plan");
        List<JsonNode> nodes = nodes(plan);
        assertThat(nodes).as("Orphan queue plan: %s", plan.toPrettyString())
                .anyMatch(node -> "idx_musician_feed_restriction_orphan_queue"
                        .equals(node.path("Index Name").asText()));
        assertThat(nodes).as("Orphan queue must not scan normal restrictions: %s", plan.toPrettyString())
                .noneMatch(node -> "Seq Scan".equals(node.path("Node Type").asText())
                        && "tbl_musician_feed_restriction".equals(node.path("Relation Name").asText()));
        assertThat(nodes).filteredOn(node -> "tbl_musician_feed_restriction"
                        .equals(node.path("Relation Name").asText()))
                .as("Only the two indexed orphan rows may be visited: %s", plan.toPrettyString())
                .isNotEmpty().allMatch(node -> node.path("Actual Rows").asInt()
                        + node.path("Rows Removed by Filter").asInt() <= 2);
    }

    private List<JsonNode> nodes(JsonNode node) {
        List<JsonNode> result = new ArrayList<>(List.of(node));
        node.path("Plans").forEach(child -> result.addAll(nodes(child)));
        return result;
    }
}
