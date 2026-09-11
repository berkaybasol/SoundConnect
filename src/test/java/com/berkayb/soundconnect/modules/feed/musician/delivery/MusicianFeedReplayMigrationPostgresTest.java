package com.berkayb.soundconnect.modules.feed.musician.delivery;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class MusicianFeedReplayMigrationPostgresTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("musician_feed_replay_migration")
            .withUsername("soundconnect").withPassword("soundconnect");

    private final UUID viewer = UUID.randomUUID();

    @BeforeEach
    void hibernateFirstSchema() throws Exception {
        execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public");
        execute("CREATE TABLE tbl_user(id uuid PRIMARY KEY)");
        execute("INSERT INTO tbl_user(id) VALUES ('" + viewer + "')");
        execute("""
                CREATE TABLE tbl_musician_feed_page_replay(
                    id uuid PRIMARY KEY, viewer_user_id uuid NOT NULL, feed_session_id uuid NOT NULL,
                    request_position bigint NOT NULL, request_fingerprint varchar(64) NOT NULL,
                    requested_limit integer NOT NULL, supported_types varchar(768) NOT NULL,
                    schema_version integer NOT NULL, algorithm_version varchar(64) NOT NULL,
                    response_json text NOT NULL, created_at timestamptz NOT NULL,
                    expires_at timestamptz NOT NULL)
                """);
    }

    @Test
    void repeatMigrationAddsAllReplayFencesAndRegistration() throws Exception {
        String migration = Files.readString(Path.of("scripts/db/2026-09-11-musician-feed-replay.sql"));
        execute(migration);
        execute(migration);

        assertThat(number("select count(*) from soundconnect_schema_migrations "
                + "where migration_id='2026-09-11-musician-feed-replay'"))
                .isEqualTo(1);
        assertThat(number("select count(*) from pg_constraint where conrelid="
                + "'public.tbl_musician_feed_page_replay'::regclass and conname in ("
                + "'fk_musician_feed_replay_viewer','uk_musician_feed_replay_position',"
                + "'uk_musician_feed_replay_fingerprint','ck_musician_feed_replay_shape')"))
                .isEqualTo(4);
        assertThat(number("select count(*) from information_schema.columns where table_schema='public' "
                + "and table_name='tbl_musician_feed_page_replay' and column_name='response_json' "
                + "and data_type='text'"))
                .isEqualTo(1);

        UUID session = UUID.randomUUID();
        insertReplay(UUID.randomUUID(), viewer, session, 0, "a".repeat(43), 20, "TRACK", "{}");

        assertThatThrownBy(() -> insertReplay(UUID.randomUUID(), viewer, session, 0,
                "b".repeat(43), 20, "TRACK", "{}"))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> insertReplay(UUID.randomUUID(), viewer, session, 1,
                "a".repeat(43), 20, "TRACK", "{}"))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> insertReplay(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 0,
                "c".repeat(43), 20, "TRACK", "{}"))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> insertReplay(UUID.randomUUID(), viewer, UUID.randomUUID(), 0,
                "d".repeat(43), 0, "TRACK", "{}"))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> insertReplay(UUID.randomUUID(), viewer, UUID.randomUUID(), 0,
                "e".repeat(43), 20, "TRACK", "{\"data\":\"" + "x".repeat(4_194_304) + "\"}"))
                .isInstanceOf(SQLException.class);

        execute("DELETE FROM tbl_user WHERE id='" + viewer + "'");
        assertThat(number("select count(*) from tbl_musician_feed_page_replay")).isZero();
    }

    private void insertReplay(UUID id, UUID userId, UUID sessionId, long position,
                              String fingerprint, int limit, String types, String response) throws SQLException {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
                insert into tbl_musician_feed_page_replay(
                    id,viewer_user_id,feed_session_id,request_position,request_fingerprint,
                    requested_limit,supported_types,schema_version,algorithm_version,response_json,
                    created_at,expires_at)
                values(?,?,?,?,?,?,?,?,?,?,?,?)
                """)) {
            Instant created = Instant.parse("2026-09-11T12:00:00Z");
            statement.setObject(1, id);
            statement.setObject(2, userId);
            statement.setObject(3, sessionId);
            statement.setLong(4, position);
            statement.setString(5, fingerprint);
            statement.setInt(6, limit);
            statement.setString(7, types);
            statement.setInt(8, 1);
            statement.setString(9, "musician-v1.0.0");
            statement.setString(10, response);
            statement.setTimestamp(11, Timestamp.from(created));
            statement.setTimestamp(12, Timestamp.from(created.plusSeconds(60)));
            statement.executeUpdate();
        }
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private long number(String sql) throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
