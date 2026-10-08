package com.berkayb.soundconnect.modules.feed.musician.provider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class MusicianFeedIndexMigrationPostgresTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("musician_feed_indexes")
            .withUsername("soundconnect").withPassword("soundconnect");

    @BeforeEach
    void schema() throws Exception {
        execute("DROP SCHEMA public CASCADE");
        execute("CREATE SCHEMA public");
        execute("CREATE TABLE tbl_tracks(id uuid primary key,created_at timestamptz not null,"
                + "owner_type varchar(32),owner_id uuid,media_asset_id uuid)");
        execute("CREATE TABLE tbl_profile_media(id uuid primary key,created_at timestamptz not null,"
                + "profile_type varchar(32),profile_id uuid,media_asset_id uuid,role varchar(32))");
        execute("CREATE TABLE tbl_follow(id uuid primary key,created_at timestamp,followed_at timestamp,"
                + "follower_id uuid,following_id uuid)");
        execute("CREATE TABLE tbl_like(id uuid primary key,created_at timestamptz not null,"
                + "user_id uuid,target_type varchar(50),target_id uuid)");
        execute("CREATE TABLE tbl_comment(id uuid primary key,created_at timestamptz not null,"
                + "user_id uuid,target_type varchar(50),target_id uuid,is_deleted boolean not null)");
        execute("CREATE TABLE tbl_event_audience_intent(user_id uuid,event_id uuid,published_at timestamptz,"
                + "post_id uuid,published_on_profile boolean not null)");
        execute("CREATE TABLE tbl_overthinking_profile_share(id uuid primary key,published_at timestamptz,"
                + "owner_user_id uuid,listener_profile_id uuid,source_post_id uuid)");
        execute("CREATE TABLE tbl_table_group_profile_share(id uuid primary key,published_at timestamptz,"
                + "owner_user_id uuid,listener_profile_id uuid,table_group_id uuid)");
        execute("CREATE TABLE tbl_event(id uuid primary key,event_date date,start_time time,venue_id uuid,"
                + "musician_profile_id uuid,band_id uuid,created_at timestamptz,event_origin varchar(20),"
                + "venue_calendar_approved boolean not null)");
        execute("CREATE TABLE tbl_musician_feed_delivery(viewer_user_id uuid not null,"
                + "feed_session_id uuid not null,target_type varchar(48) not null,target_id uuid not null,"
                + "item_type varchar(48) not null,item_id varchar(256) not null)");
    }

    @Test
    void buildsOnlineIndexesOutsideATransactionAndSupportsFeedOrderPlan() throws Exception {
        String migration = Files.readString(Path.of("scripts/db/2026-09-11-musician-feed-indexes.sql"));
        assertThat(migration).contains("CREATE INDEX CONCURRENTLY").doesNotContain("BEGIN;");

        executeMigrationStatements(migration);
        executeMigrationStatements(migration);

        assertThat(number("select count(*) from pg_indexes where indexname like 'idx_feed_%'"))
                .isEqualTo(10);
        assertThat(number("select count(*) from pg_index i join pg_class c on c.oid=i.indexrelid "
                + "where c.relname like 'idx_feed_%' and i.indisvalid and i.indisready"))
                .isEqualTo(10);
        assertThat(number("select count(*) from soundconnect_schema_migrations "
                + "where migration_id='2026-09-11-musician-feed-indexes'"))
                .isEqualTo(1);

        execute("insert into tbl_tracks(id,created_at,owner_type,owner_id,media_asset_id) "
                + "select md5('track-'||n)::uuid,now()-(n||' seconds')::interval,'MUSICIAN_PROFILE',"
                + "md5('owner-'||n)::uuid,md5('media-'||n)::uuid from generate_series(1,20000) n");
        execute("analyze tbl_tracks");
        assertThat(plan("explain select id from tbl_tracks "
                + "where created_at<=now() order by created_at desc,id desc limit 160"))
                .contains("idx_feed_tracks_created");

        execute("insert into tbl_musician_feed_delivery(viewer_user_id,feed_session_id,target_type,"
                + "target_id,item_type,item_id) select md5('viewer-'||(n%10))::uuid,"
                + "md5('session-'||(n%100))::uuid,'MEDIA',md5('target-'||n)::uuid,'TRACK',"
                + "'TRACK:'||n from generate_series(1,20000) n");
        execute("analyze tbl_musician_feed_delivery");
        assertThat(plan("explain select 1 from tbl_musician_feed_delivery where viewer_user_id="
                + "md5('viewer-1')::uuid and feed_session_id=md5('session-1')::uuid "
                + "and target_type='MEDIA' and target_id=md5('target-1')::uuid "
                + "and item_type<>'ACTIVITY_COMMENT' limit 1"))
                .contains("idx_feed_delivery_session_target");
    }

    private void executeMigrationStatements(String sql) throws SQLException {
        String withoutComments = Arrays.stream(sql.split("\\R"))
                .filter(line -> !line.stripLeading().startsWith("--"))
                .reduce("", (left, right) -> left + "\n" + right);
        try (Connection connection = connection(); Statement command = connection.createStatement()) {
            connection.setAutoCommit(true);
            for (String statement : withoutComments.split(";")) {
                if (!statement.isBlank()) command.execute(statement);
            }
        }
    }

    private String plan(String sql) throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("set enable_seqscan=off");
            try (ResultSet rows = statement.executeQuery(sql)) {
                StringBuilder value = new StringBuilder();
                while (rows.next()) value.append(rows.getString(1)).append('\n');
                return value.toString();
            }
        }
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(true);
            statement.execute(sql);
        }
    }

    private long number(String sql) throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
