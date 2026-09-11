package com.berkayb.soundconnect.modules.feed.musician.feedback;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class MusicianFeedFeedbackMigrationPostgresTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("musician_feed_feedback_migration")
            .withUsername("soundconnect").withPassword("soundconnect");

    private final UUID viewer = UUID.randomUUID();
    private final UUID author = UUID.randomUUID();
    private final UUID delivery = UUID.randomUUID();

    @BeforeEach
    void schema() throws Exception {
        execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public;");
        execute("CREATE TABLE tbl_user(id uuid PRIMARY KEY);");
        execute("INSERT INTO tbl_user(id) VALUES ('" + viewer + "'),('" + author + "')");
        execute(deliveryMigration());
        execute("""
                INSERT INTO tbl_musician_feed_delivery(
                    id,viewer_user_id,feed_session_id,item_id,item_type,feed_lane,target_type,target_id,
                    feedback_capabilities,schema_version,algorithm_version,absolute_position,
                    evidence_json,delivered_at,expires_at,purge_after)
                VALUES ('%s','%s','%s','TRACK:one','TRACK','FOLLOWING','MEDIA','%s',
                    'HIDE,REPORT,SHOW_LESS',1,'test-v1',0,'{}',now(),now()+interval '1 hour',
                    now()+interval '90 days')
                """.formatted(delivery, viewer, UUID.randomUUID(), UUID.randomUUID()));
    }

    @Test
    void migrationIsAdditiveIdempotentAndPreservesFeedback() throws Exception {
        execute(migration());
        execute("""
                INSERT INTO tbl_musician_feed_feedback(
                    id,viewer_user_id,action,scope_key,item_id,item_type,delivery_id,reason,created_at,updated_at)
                VALUES ('%s','%s','REPORT','ITEM:TRACK:one','TRACK:one','TRACK','%s','unsafe',now(),now())
                """.formatted(UUID.randomUUID(), viewer, delivery));

        execute(migration());

        assertThat(number("SELECT count(*) FROM tbl_musician_feed_feedback")).isEqualTo(1);
        assertThat(number("SELECT count(*) FROM soundconnect_schema_migrations "
                + "WHERE migration_id='2026-09-11-musician-feed-feedback'")).isEqualTo(1);
        assertThat(number("SELECT count(*) FROM pg_indexes WHERE indexname LIKE 'idx_musician_feed_feedback_%'"))
                .isEqualTo(3);
    }

    @Test
    void uniquenessAndShapeConstraintsRejectCorruptFeedback() throws Exception {
        execute(migration());
        String first = itemInsert("HIDE", "ITEM:TRACK:one", "TRACK:one", "TRACK");
        execute(first);

        assertThatThrownBy(() -> execute(itemInsert("HIDE", "ITEM:TRACK:one", "TRACK:one", "TRACK")))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute("""
                INSERT INTO tbl_musician_feed_feedback(
                    id,viewer_user_id,action,scope_key,created_at,updated_at)
                VALUES ('%s','%s','MUTE_AUTHOR','AUTHOR:broken',now(),now())
                """.formatted(UUID.randomUUID(), viewer)))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute(itemInsert("UNKNOWN", "ITEM:x", "x", "TRACK")))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void muteUsesStableProfileIdentityAndViewerErasureCascades() throws Exception {
        execute(migration());
        execute(muteInsert("VENUE", author));

        // The profile is polymorphic and deliberately does not FK to tbl_user.
        execute("DELETE FROM tbl_user WHERE id='" + author + "'");
        assertThat(number("SELECT count(*) FROM tbl_musician_feed_feedback")).isEqualTo(1);

        execute("DELETE FROM tbl_user WHERE id='" + viewer + "'");
        assertThat(number("SELECT count(*) FROM tbl_musician_feed_feedback")).isZero();
    }

    @Test
    void migrationNormalizesHibernateFirstTableWithConstraintsAndForeignKey() throws Exception {
        execute("""
                CREATE TABLE tbl_musician_feed_feedback(
                    id uuid PRIMARY KEY,
                    viewer_user_id uuid NOT NULL,
                    action varchar(24) NOT NULL,
                    scope_key varchar(320) NOT NULL,
                    item_id varchar(256),
                    item_type varchar(48),
                    author_profile_type varchar(24),
                    author_profile_id uuid,
                    reason varchar(500),
                    created_at timestamptz NOT NULL,
                    updated_at timestamptz NOT NULL
                )
                """);

        execute(migration());

        assertThat(number("""
                SELECT count(*) FROM pg_constraint
                WHERE conrelid='tbl_musician_feed_feedback'::regclass
                  AND conname IN ('fk_musician_feed_feedback_viewer',
                                  'uk_musician_feed_feedback_scope',
                                  'ck_musician_feed_feedback_action',
                                  'ck_musician_feed_feedback_reason',
                                  'ck_musician_feed_feedback_profile_type',
                                  'ck_musician_feed_feedback_shape',
                                  'fk_musician_feed_feedback_delivery')
                """)).isEqualTo(7);
        assertThatThrownBy(() -> execute(itemInsert("HIDE", "ITEM:TRACK:ghost", "TRACK:ghost", "TRACK")
                .replace("'" + viewer + "'", "'" + UUID.randomUUID() + "'")))
                .isInstanceOf(SQLException.class);
    }

    private String itemInsert(String action, String scope, String itemId, String itemType) {
        return """
                INSERT INTO tbl_musician_feed_feedback(
                    id,viewer_user_id,action,scope_key,item_id,item_type,delivery_id,reason,created_at,updated_at)
                VALUES ('%s','%s','%s','%s','%s','%s','%s',NULL,now(),now())
                """.formatted(UUID.randomUUID(), viewer, action, scope, itemId, itemType, delivery);
    }

    private String muteInsert(String type, UUID profileId) {
        return """
                INSERT INTO tbl_musician_feed_feedback(
                    id,viewer_user_id,action,scope_key,author_profile_type,author_profile_id,
                    created_at,updated_at)
                VALUES ('%s','%s','MUTE_AUTHOR','AUTHOR:%s:%s','%s','%s',now(),now())
                """.formatted(UUID.randomUUID(), viewer, type, profileId, type, profileId);
    }

    private static String migration() throws Exception {
        return Files.readString(Path.of("scripts/db/2026-09-11-musician-feed-feedback.sql"));
    }

    private static String deliveryMigration() throws Exception {
        return Files.readString(Path.of("scripts/db/2026-09-11-musician-feed-delivery.sql"));
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
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
}
