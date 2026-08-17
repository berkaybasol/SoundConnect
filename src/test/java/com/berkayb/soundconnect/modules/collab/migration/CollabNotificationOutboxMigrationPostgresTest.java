package com.berkayb.soundconnect.modules.collab.migration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class CollabNotificationOutboxMigrationPostgresTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("soundconnect_collab_notification_outbox")
            .withUsername("soundconnect")
            .withPassword("soundconnect");

    @BeforeEach
    void resetSchema() throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS tbl_collab_notification_outbox CASCADE");
            statement.execute("DROP TABLE IF EXISTS tbl_notification CASCADE");
            statement.execute("""
                    CREATE TABLE tbl_notification (
                        id uuid PRIMARY KEY,
                        recipient_id uuid,
                        created_at timestamp without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
        }
    }

    @Test
    void migrationIsRerunnableAndAddsOutboxAndConsumerDedupeFence() throws Exception {
        String migration = migrationSql();

        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO tbl_notification (id, recipient_id, created_at)
                    VALUES (
                        '00000000-0000-0000-0000-000000000010',
                        '00000000-0000-0000-0000-000000000110',
                        TIMESTAMP '2026-08-11 08:30:00'
                    )
                    """);
        }

        execute(migration);
        execute(migration);

        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            assertThat(singleInt(statement, """
                    SELECT count(*)
                      FROM information_schema.tables
                     WHERE table_schema = current_schema()
                       AND table_name = 'tbl_collab_notification_outbox'
                    """)).isEqualTo(1);
            assertThat(singleInt(statement, """
                    SELECT count(*)
                      FROM information_schema.columns
                     WHERE table_schema = current_schema()
                       AND table_name = 'tbl_notification'
                       AND column_name = 'source_event_id'
                    """)).isEqualTo(1);
            assertThat(singleInt(statement, """
                    SELECT count(*)
                      FROM information_schema.columns
                     WHERE table_schema = current_schema()
                       AND table_name = 'tbl_notification'
                       AND column_name = 'occurred_at'
                       AND data_type = 'timestamp with time zone'
                       AND is_nullable = 'NO'
                    """)).isEqualTo(1);
            assertThat(singleInt(statement, """
                    SELECT count(*)
                      FROM tbl_notification
                     WHERE id = '00000000-0000-0000-0000-000000000010'
                       AND occurred_at = created_at AT TIME ZONE 'UTC'
                    """)).isEqualTo(1);
            assertThat(singleInt(statement, """
                    SELECT count(*)
                      FROM pg_indexes
                     WHERE schemaname = current_schema()
                       AND indexname IN (
                           'idx_collab_notification_outbox_due',
                           'idx_collab_notification_outbox_lease',
                           'uk_notification_source_event_id',
                           'idx_notification_recipient_occurred'
                       )
                    """)).isEqualTo(4);
            assertThat(singleString(statement, """
                    SELECT indexdef
                      FROM pg_indexes
                     WHERE schemaname = current_schema()
                       AND indexname = 'idx_notification_recipient_occurred'
                    """)).contains("(recipient_id, occurred_at DESC, id DESC)");

            statement.execute("""
                    INSERT INTO tbl_notification (id, recipient_id, source_event_id, occurred_at)
                    VALUES
                        ('00000000-0000-0000-0000-000000000001',
                         '00000000-0000-0000-0000-000000000111',
                         '00000000-0000-0000-0000-000000000101',
                         CURRENT_TIMESTAMP)
                    """);
            assertThatThrownBy(() -> statement.execute("""
                    INSERT INTO tbl_notification (id, recipient_id, source_event_id, occurred_at)
                    VALUES
                        ('00000000-0000-0000-0000-000000000002',
                         '00000000-0000-0000-0000-000000000112',
                         '00000000-0000-0000-0000-000000000101',
                         CURRENT_TIMESTAMP)
                    """))
                    .isInstanceOf(SQLException.class)
                    .satisfies(error -> assertThat(((SQLException) error).getSQLState()).isEqualTo("23505"));
        }
    }

    @Test
    void migrationReconcilesChecksWhenHibernateCreatedTheOutboxTableFirst() throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE tbl_collab_notification_outbox (
                        event_id uuid PRIMARY KEY,
                        recipient_id uuid NOT NULL,
                        notification_type varchar(64) NOT NULL,
                        title varchar(160) NOT NULL,
                        message varchar(1000) NOT NULL,
                        payload jsonb NOT NULL,
                        email_force boolean NOT NULL,
                        occurred_at timestamp with time zone NOT NULL,
                        status varchar(24) NOT NULL,
                        attempt_count integer NOT NULL,
                        next_attempt_at timestamp with time zone NOT NULL,
                        lease_owner varchar(100),
                        lease_until timestamp with time zone,
                        last_error_type varchar(200),
                        published_at timestamp with time zone,
                        created_at timestamp with time zone NOT NULL,
                        updated_at timestamp with time zone NOT NULL
                    )
                    """);
        }

        execute(migrationSql());
        execute(migrationSql());

        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            assertThat(singleInt(statement, """
                    SELECT count(*)
                      FROM pg_constraint
                     WHERE conrelid = 'tbl_collab_notification_outbox'::regclass
                       AND conname IN (
                           'ck_collab_notification_outbox_status',
                           'ck_collab_notification_outbox_attempt_count',
                           'ck_collab_notification_outbox_payload',
                           'ck_collab_notification_outbox_title',
                           'ck_collab_notification_outbox_message',
                           'ck_collab_notification_outbox_lease',
                           'ck_collab_notification_outbox_published',
                           'ck_collab_notification_outbox_error_type'
                       )
                    """)).isEqualTo(8);

            assertThatThrownBy(() -> statement.execute("""
                    INSERT INTO tbl_collab_notification_outbox (
                        event_id, recipient_id, notification_type, title, message,
                        payload, email_force, occurred_at, status, attempt_count,
                        next_attempt_at, created_at, updated_at
                    ) VALUES (
                        '00000000-0000-0000-0000-000000000201',
                        '00000000-0000-0000-0000-000000000202',
                        'COLLAB_APPLICATION_RECEIVED', 'title', 'message', '{}',
                        false, CURRENT_TIMESTAMP, 'BROKEN', 0,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                    )
                    """))
                    .isInstanceOf(SQLException.class)
                    .satisfies(error -> assertThat(((SQLException) error).getSQLState()).isEqualTo("23514"));
        }
    }

    private static String migrationSql() throws Exception {
        return Files.readString(Path.of(
                System.getProperty("user.dir"),
                "scripts", "db", "2026-08-11-collab-notification-outbox.sql"
        ));
    }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static int singleInt(Statement statement, String sql) throws SQLException {
        try (ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getInt(1);
        }
    }

    private static String singleString(Statement statement, String sql) throws SQLException {
        try (ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()
        );
    }
}
