package com.berkayb.soundconnect.modules.feed.musician.feedback;

import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedItemResponse;
import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedItemType;
import com.berkayb.soundconnect.modules.feed.musician.candidate.MusicianFeedCandidate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
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
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Testcontainers(disabledWithoutDocker = true)
class MusicianFeedFeedbackReaderPostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("musician_feedback_reader").withUsername("soundconnect").withPassword("soundconnect");

    private static final Instant OLD = Instant.parse("2020-01-01T00:00:00Z");
    private final UUID viewer = UUID.randomUUID();
    private final UUID otherViewer = UUID.randomUUID();
    private JdbcTemplate sql;
    private NamedParameterJdbcTemplate jdbc;
    private MusicianFeedFeedbackReader reader;

    @BeforeEach
    void setUp() throws Exception {
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        sql = new JdbcTemplate(dataSource);
        jdbc = spy(new NamedParameterJdbcTemplate(dataSource));
        reader = new MusicianFeedFeedbackReader(jdbc);
        sql.execute("drop schema public cascade; create schema public");
        sql.execute("create table tbl_user(id uuid primary key)");
        sql.execute("create table tbl_musician_feed_delivery(id uuid primary key)");
        sql.update("insert into tbl_user(id) values (?),(?)", viewer, otherViewer);
        sql.execute(Files.readString(Path.of("scripts/db/2026-09-11-musician-feed-feedback.sql")));
        sql.execute(migration());
    }

    @Test
    void moreThanFiveThousandMixedPreferencesKeepOldHidesReportsAndMutesWithoutLoadingHistory() {
        bulkMutes(viewer, 6_000);
        bulkItems(viewer, "HIDE", "TRACK", 1_200);
        bulkItems(viewer, "SHOW_LESS", "TRACK", 1_200);
        String oldProfile = "PROFILE:" + UUID.randomUUID();
        String reported = "TRACK:" + UUID.randomUUID();
        item(viewer, "HIDE", oldProfile, "PROFILE");
        item(viewer, "REPORT", reported, "TRACK");
        var mutedProfile = UUID.randomUUID();
        mute(viewer, mutedProfile);
        var ranking = reader.ranking(viewer);
        assertThat(ranking.hiddenItemIds()).isEmpty();
        assertThat(ranking.mutedAuthorKeys()).isEmpty();
        assertThat(ranking.showLessCounts()).containsExactlyEntriesOf(Map.of(MusicianFeedItemType.TRACK, 10));
        var organic = List.of(candidate(oldProfile, null), candidate("TRACK:muted", mutedProfile),
                candidate("TRACK:unrelated", UUID.randomUUID()));
        var promotions = List.of(candidate(reported, null));
        long before = rowCount();
        clearInvocations(jdbc);

        var snapshot = reader.forCandidates(viewer, ranking, organic, promotions);

        assertThat(snapshot.hiddenItemIds()).containsExactlyInAnyOrder(oldProfile, reported);
        assertThat(snapshot.mutedAuthorKeys()).containsExactly("MUSICIAN:" + mutedProfile);
        assertThat(snapshot.rankingContextVersion()).isEqualTo(ranking.rankingContextVersion());
        verify(jdbc, times(1)).query(anyString(), any(SqlParameterSource.class), any(RowCallbackHandler.class));
        assertThat(rowCount()).isEqualTo(before).isGreaterThan(5_000);
        var other = reader.forCandidates(otherViewer, reader.ranking(otherViewer), organic, promotions);
        assertThat(other.hiddenItemIds()).isEmpty();
        assertThat(other.mutedAuthorKeys()).isEmpty();
        assertThat(other.showLessCounts()).isEmpty();
    }

    @Test
    void muteOnlyHistoryDoesNotEnterTheRankingSnapshot() {
        var emptyVersion = reader.ranking(viewer).rankingContextVersion();
        bulkMutes(viewer, 6_001);

        var ranking = reader.ranking(viewer);

        assertThat(ranking.showLessCounts()).isEmpty();
        assertThat(ranking.hiddenItemIds()).isEmpty();
        assertThat(ranking.mutedAuthorKeys()).isEmpty();
        assertThat(ranking.rankingContextVersion()).isEqualTo(emptyVersion);
        assertThat(rowCount()).isEqualTo(6_001);
    }

    @Test
    void rankingVersionChangesOnlyWithEffectiveCappedWeights() {
        bulkItems(viewer, "SHOW_LESS", "TRACK", 9);
        var nine = reader.ranking(viewer);
        item(viewer, "SHOW_LESS", "TRACK:tenth", "TRACK");
        var ten = reader.ranking(viewer);
        assertThat(nine.showLessCounts()).containsEntry(MusicianFeedItemType.TRACK, 9);
        assertThat(ten.showLessCounts()).containsEntry(MusicianFeedItemType.TRACK, 10);
        assertThat(ten.rankingContextVersion()).isNotEqualTo(nine.rankingContextVersion());

        item(viewer, "SHOW_LESS", "TRACK:eleventh", "TRACK");
        bulkMutes(viewer, 6_001);
        item(viewer, "REPORT", "TRACK:report", "TRACK");
        sql.update("update tbl_musician_feed_feedback set updated_at=now(),reason='changed' where action='SHOW_LESS'");
        assertThat(reader.ranking(viewer)).isEqualTo(ten);

        item(viewer, "SHOW_LESS", "EVENT:first", "EVENT");
        var eventPreference = reader.ranking(viewer);
        assertThat(eventPreference.showLessCounts()).containsEntry(MusicianFeedItemType.EVENT, 1);
        assertThat(eventPreference.rankingContextVersion()).isNotEqualTo(ten.rankingContextVersion());
        bulkItems(otherViewer, "SHOW_LESS", "TRACK", 3);
        assertThat(reader.ranking(otherViewer).showLessCounts()).containsExactlyEntriesOf(Map.of(MusicianFeedItemType.TRACK, 3));
    }

    @Test
    void rankingAndCandidatePlansUseLookupIndexesWithLargeUnrelatedHistory() throws Exception {
        bulkMutes(viewer, 6_000);
        bulkItems(viewer, "SHOW_LESS", "TRACK", 3_000);
        bulkItems(otherViewer, "SHOW_LESS", "EVENT", 3_000);
        item(viewer, "HIDE", "PROFILE:old", "PROFILE");
        sql.execute("analyze tbl_musician_feed_feedback");
        JsonNode rankingPlan = plan(MusicianFeedFeedbackReader.RANKING_SQL,
                new MapSqlParameterSource("viewerId", viewer));
        List<JsonNode> rankingNodes = nodes(rankingPlan);
        assertThat(rankingNodes).as("Ranking plan: %s", rankingPlan.toPrettyString())
                .anyMatch(node -> "idx_musician_feed_feedback_ranking_lookup"
                .equals(node.path("Index Name").asText()));
        assertThat(rankingNodes).as("Ranking must not rescan feedback history: %s", rankingPlan.toPrettyString())
                .noneMatch(node -> "Seq Scan".equals(node.path("Node Type").asText())
                        && "tbl_musician_feed_feedback".equals(node.path("Relation Name").asText()));
        assertThat(rankingNodes).filteredOn(node -> "Limit".equals(node.path("Node Type").asText()))
                .isNotEmpty().allMatch(node -> node.path("Actual Rows").asInt() <= 10);
        clearInvocations(jdbc);
        reader.forCandidates(viewer, reader.ranking(viewer), List.of(candidate("PROFILE:old", null)), List.of());
        var statements = ArgumentCaptor.forClass(String.class);
        var parameters = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc, times(2)).query(statements.capture(), parameters.capture(), any(RowCallbackHandler.class));
        JsonNode candidatePlan = plan(statements.getAllValues().get(1), parameters.getAllValues().get(1));
        assertThat(nodes(candidatePlan)).as("Candidate suppression plan: %s", candidatePlan.toPrettyString())
                .anyMatch(node -> "uk_musician_feed_feedback_scope"
                .equals(node.path("Index Name").asText()));
    }

    @Test
    void lookupMigrationIsAdditiveAndIdempotent() throws Exception {
        mute(viewer, UUID.randomUUID());
        item(viewer, "HIDE", "PROFILE:kept", "PROFILE");
        item(viewer, "REPORT", "TRACK:kept", "TRACK");
        sql.execute(migration());
        sql.execute(migration());
        assertThat(rowCount()).isEqualTo(3);
        assertThat(sql.queryForObject("select count(*) from pg_indexes where indexname in "
                + "('idx_musician_feed_feedback_ranking_lookup','idx_musician_feed_feedback_item_lookup')", Long.class))
                .isEqualTo(2);
        assertThat(sql.queryForObject("select count(*) from soundconnect_schema_migrations "
                + "where migration_id='2026-09-13-musician-feed-feedback-lookup'", Long.class)).isEqualTo(1);
    }

    private void bulkMutes(UUID targetViewer, int count) {
        jdbc.update("""
                insert into tbl_musician_feed_feedback(id,viewer_user_id,action,scope_key,
                    author_profile_type,author_profile_id,created_at,updated_at)
                select gen_random_uuid(),:viewer,'MUTE_AUTHOR',
                    'AUTHOR:MUSICIAN:'||md5('muted-profile-'||n)::uuid,'MUSICIAN',
                    md5('muted-profile-'||n)::uuid,:old,:old from generate_series(1,:count) n
                """, new MapSqlParameterSource("viewer", targetViewer).addValue("count", count)
                .addValue("old", Timestamp.from(OLD)));
    }

    private void bulkItems(UUID targetViewer, String action, String type, int count) {
        jdbc.update("""
                insert into tbl_musician_feed_feedback(id,viewer_user_id,action,scope_key,
                    item_id,item_type,created_at,updated_at)
                select gen_random_uuid(),:viewer,:action,'ITEM:'||:type||':historical-'||n,
                    :type||':historical-'||n,:type,:old,:old from generate_series(1,:count) n
                """, new MapSqlParameterSource("viewer", targetViewer).addValue("action", action)
                .addValue("type", type).addValue("count", count).addValue("old", Timestamp.from(OLD)));
    }

    private void item(UUID targetViewer, String action, String itemId, String type) {
        sql.update("""
                insert into tbl_musician_feed_feedback(id,viewer_user_id,action,scope_key,
                    item_id,item_type,created_at,updated_at) values (?,?,?,?,?,?,?,?)
                """, UUID.randomUUID(), targetViewer, action, "ITEM:" + itemId, itemId, type,
                Timestamp.from(OLD), Timestamp.from(OLD));
    }

    private void mute(UUID targetViewer, UUID profileId) {
        sql.update("""
                insert into tbl_musician_feed_feedback(id,viewer_user_id,action,scope_key,
                    author_profile_type,author_profile_id,created_at,updated_at) values (?,?,?,?,?,?,?,?)
                """, UUID.randomUUID(), targetViewer, "MUTE_AUTHOR", "AUTHOR:MUSICIAN:" + profileId,
                "MUSICIAN", profileId, Timestamp.from(OLD), Timestamp.from(OLD));
    }

    private MusicianFeedCandidate candidate(String itemId, UUID authorProfile) {
        var author = authorProfile == null ? null : new MusicianFeedItemResponse.Author(UUID.randomUUID(),
                authorProfile, "MUSICIAN", "artist", "Artist", null, true);
        var type = MusicianFeedItemType.valueOf(itemId.substring(0, itemId.indexOf(':')));
        return new MusicianFeedCandidate(itemId, type, 1, OLD, null, author,
                new MusicianFeedItemResponse.Target(type == MusicianFeedItemType.PROFILE ? "PROFILE" : "MEDIA",
                        UUID.randomUUID()), null, null, List.of(), null, 0, 0, null, false);
    }

    private long rowCount() {
        return Objects.requireNonNull(sql.queryForObject("select count(*) from tbl_musician_feed_feedback", Long.class));
    }

    private String migration() throws Exception {
        return Files.readString(Path.of("scripts/db/2026-09-13-musician-feed-feedback-lookup.sql"));
    }

    private JsonNode plan(String query, SqlParameterSource parameters) throws Exception {
        return new ObjectMapper().readTree(jdbc.queryForObject(
                "explain (analyze,format json) " + query, parameters, String.class)).get(0).get("Plan");
    }

    private List<JsonNode> nodes(JsonNode node) {
        List<JsonNode> result = new ArrayList<>(List.of(node));
        node.path("Plans").forEach(child -> result.addAll(nodes(child)));
        return result;
    }
}
