package com.berkayb.soundconnect.modules.feed.musician.delivery;

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
class MusicianFeedDeliveryMigrationPostgresTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("musician_feed_delivery_migration")
            .withUsername("soundconnect").withPassword("soundconnect");

    private final UUID viewer = UUID.randomUUID();
    private final UUID delivery = UUID.randomUUID();

    @BeforeEach
    void hibernateFirstSchema() throws Exception {
        execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public;");
        execute("CREATE TABLE tbl_user(id uuid PRIMARY KEY)");
        execute("INSERT INTO tbl_user(id) VALUES ('" + viewer + "')");
        execute("""
                CREATE TABLE tbl_musician_feed_delivery(
                    id uuid PRIMARY KEY, viewer_user_id uuid NOT NULL, feed_session_id uuid NOT NULL,
                    item_id varchar(256) NOT NULL, item_type varchar(48) NOT NULL,
                    target_type varchar(48) NOT NULL, target_id uuid NOT NULL,
                    author_profile_type varchar(24), author_profile_id uuid, reason_code varchar(64),
                    feedback_capabilities varchar(160) NOT NULL, schema_version integer NOT NULL,
                    algorithm_version varchar(64) NOT NULL, absolute_position bigint NOT NULL,
                    campaign_id uuid, delivered_at timestamptz NOT NULL, expires_at timestamptz NOT NULL)
                """);
        execute("""
                CREATE TABLE tbl_musician_feed_telemetry_event(
                    id uuid PRIMARY KEY,viewer_user_id uuid NOT NULL,client_event_id uuid NOT NULL,
                    delivery_id uuid NOT NULL,event_type varchar(24) NOT NULL,
                    client_occurred_at timestamptz,recorded_at timestamptz NOT NULL)
                """);
        execute("""
                CREATE TABLE tbl_musician_feed_content_report(
                    id uuid PRIMARY KEY,viewer_user_id uuid NOT NULL,delivery_id uuid NOT NULL,
                    item_id varchar(256) NOT NULL,item_type varchar(48) NOT NULL,
                    target_type varchar(48) NOT NULL,target_id uuid NOT NULL,reason varchar(500),
                    status varchar(24) NOT NULL,reported_at timestamptz NOT NULL)
                """);
        execute("""
                INSERT INTO tbl_musician_feed_delivery(
                    id,viewer_user_id,feed_session_id,item_id,item_type,target_type,target_id,
                    feedback_capabilities,schema_version,algorithm_version,absolute_position,
                    delivered_at,expires_at)
                VALUES ('%s','%s','%s','TRACK:one','TRACK','MEDIA','%s','HIDE,REPORT',1,
                    'test-v1',0,now()-interval '100 days',now()-interval '99 days')
                """.formatted(delivery, viewer, UUID.randomUUID(), UUID.randomUUID()));
        execute("""
                INSERT INTO tbl_musician_feed_content_report(
                    id,viewer_user_id,delivery_id,item_id,item_type,target_type,target_id,status,reported_at)
                VALUES ('%s','%s','%s','TRACK:one','TRACK','MEDIA','%s','NEW',now())
                """.formatted(UUID.randomUUID(), viewer, delivery, UUID.randomUUID()));
    }

    @Test
    void normalizesOldRowsAndPreservesDurableFeedbackAndReportEvidenceOnPurge() throws Exception {
        execute(deliveryMigration());
        execute(feedbackMigration());
        execute("""
                INSERT INTO tbl_musician_feed_feedback(
                    id,viewer_user_id,action,scope_key,item_id,item_type,delivery_id,created_at,updated_at)
                VALUES ('%s','%s','HIDE','ITEM:TRACK:one','TRACK:one','TRACK','%s',now(),now())
                """.formatted(UUID.randomUUID(), viewer, delivery));
        execute("""
                INSERT INTO tbl_musician_feed_telemetry_event(
                    id,viewer_user_id,client_event_id,delivery_id,event_type,recorded_at)
                VALUES ('%s','%s','%s','%s','IMPRESSION',now())
                """.formatted(UUID.randomUUID(), viewer, UUID.randomUUID(), delivery));

        assertThat(number("select count(*) from tbl_musician_feed_delivery "
                + "where evidence_json is not null and purge_after is not null "
                + "and feed_lane='FOLLOWING'")).isEqualTo(1);
        assertThat(number("select count(*) from information_schema.columns where table_schema='public' "
                + "and table_name='tbl_musician_feed_delivery' and column_name='feed_lane' "
                + "and is_nullable='NO'")).isEqualTo(1);
        assertThat(number("select count(*) from tbl_musician_feed_content_report "
                + "where evidence_json is not null")).isEqualTo(1);

        execute("DELETE FROM tbl_musician_feed_delivery WHERE id='" + delivery + "'");

        assertThat(number("select count(*) from tbl_musician_feed_feedback where delivery_id is null"))
                .isEqualTo(1);
        assertThat(number("select count(*) from tbl_musician_feed_content_report "
                + "where delivery_id is null and evidence_json is not null")).isEqualTo(1);
        assertThat(number("select count(*) from tbl_musician_feed_telemetry_event")).isZero();
    }

    @Test
    void constraintsRemainIdempotentAndRejectInvalidRetentionShape() throws Exception {
        execute(deliveryMigration());
        execute(deliveryMigration());

        assertThat(number("select count(*) from soundconnect_schema_migrations "
                + "where migration_id='2026-09-11-musician-feed-delivery'")).isEqualTo(1);
        assertThatThrownBy(() -> execute("update tbl_musician_feed_delivery set purge_after=delivered_at"))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void normalizesAndConstrainsOneTelemetryEventPerDeliveredImpression() throws Exception {
        execute("""
                INSERT INTO tbl_musician_feed_telemetry_event(
                    id,viewer_user_id,client_event_id,delivery_id,event_type,recorded_at)
                VALUES
                    ('%s','%s','%s','%s','IMPRESSION',now()-interval '1 second'),
                    ('%s','%s','%s','%s','IMPRESSION',now())
                """.formatted(UUID.randomUUID(), viewer, UUID.randomUUID(), delivery,
                UUID.randomUUID(), viewer, UUID.randomUUID(), delivery));

        execute(deliveryMigration());
        execute(deliveryMigration());

        assertThat(number("select count(*) from tbl_musician_feed_telemetry_event "
                + "where viewer_user_id='" + viewer + "' and delivery_id='" + delivery
                + "' and event_type='IMPRESSION'")).isEqualTo(1);
        assertThat(number("select count(*) from pg_constraint "
                + "where conrelid='tbl_musician_feed_telemetry_event'::regclass "
                + "and conname='uk_musician_feed_telemetry_delivery_event'")).isEqualTo(1);
        assertThatThrownBy(() -> execute("""
                INSERT INTO tbl_musician_feed_telemetry_event(
                    id,viewer_user_id,client_event_id,delivery_id,event_type,recorded_at)
                VALUES ('%s','%s','%s','%s','IMPRESSION',now())
                """.formatted(UUID.randomUUID(), viewer, UUID.randomUUID(), delivery)))
                .isInstanceOf(SQLException.class);
    }

    private static String deliveryMigration() throws Exception {
        return Files.readString(Path.of("scripts/db/2026-09-11-musician-feed-delivery.sql"));
    }

    private static String feedbackMigration() throws Exception {
        return Files.readString(Path.of("scripts/db/2026-09-11-musician-feed-feedback.sql"));
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
