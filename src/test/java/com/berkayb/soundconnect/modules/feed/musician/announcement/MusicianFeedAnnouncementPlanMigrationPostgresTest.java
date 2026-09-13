package com.berkayb.soundconnect.modules.feed.musician.announcement;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class MusicianFeedAnnouncementPlanMigrationPostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("announcement_feed_plan_migration").withUsername("soundconnect").withPassword("soundconnect");

    @Test void repeatedMigrationExpandsLegacyHibernateEnumWithoutResettingAnyHide() throws Exception {
        UUID viewer = new UUID(9, 1);
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement sql = connection.createStatement()) {
            sql.execute("CREATE TABLE tbl_user(id uuid primary key)");
            sql.execute("INSERT INTO tbl_user VALUES ('" + viewer + "')");
            sql.execute(Files.readString(Path.of("scripts/db/2026-09-11-musician-feed-delivery.sql")));
            sql.execute(Files.readString(Path.of("scripts/db/2026-09-11-musician-feed-feedback.sql")));
            sql.execute("ALTER TABLE tbl_musician_feed_feedback ADD CONSTRAINT tbl_musician_feed_feedback_item_type_check CHECK(item_type IS NULL OR item_type='TRACK')");
            insert(sql, viewer, "TRACK:existing", "TRACK");
            assertThatThrownBy(() -> insert(sql, viewer, "ANNOUNCEMENT:new", "ANNOUNCEMENT"))
                    .isInstanceOf(SQLException.class);
            String migration = Files.readString(Path.of("scripts/db/2026-09-13-announcement-feed-plan.sql"));
            sql.execute(migration);
            insert(sql, viewer, "ANNOUNCEMENT:new", "ANNOUNCEMENT");
            String before = fingerprint(sql);
            sql.execute(migration);
            assertThat(fingerprint(sql)).isEqualTo(before);
            try (var rows = sql.executeQuery("SELECT count(*) FROM soundconnect_schema_migrations WHERE migration_id='2026-09-13-announcement-feed-plan'")) {
                rows.next();
                assertThat(rows.getInt(1)).isEqualTo(1);
            }
            assertThatThrownBy(() -> insert(sql, viewer, "UNKNOWN:bad", "UNKNOWN")).isInstanceOf(SQLException.class);
        }
    }

    private static void insert(Statement sql, UUID viewer, String itemId, String type) throws SQLException {
        sql.execute("INSERT INTO tbl_musician_feed_feedback(id,viewer_user_id,action,scope_key,item_id,item_type,created_at,updated_at) VALUES ('"
                + UUID.randomUUID() + "','" + viewer + "','HIDE','ITEM:" + itemId + "','" + itemId + "','" + type + "',now(),now())");
    }
    private static String fingerprint(Statement sql) throws SQLException {
        try (var rows = sql.executeQuery("SELECT md5(string_agg(row_to_json(f)::text,'|' ORDER BY id)) FROM tbl_musician_feed_feedback f")) {
            rows.next();
            return rows.getString(1);
        }
    }
}
