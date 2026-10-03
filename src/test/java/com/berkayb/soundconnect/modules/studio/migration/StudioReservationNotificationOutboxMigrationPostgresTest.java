package com.berkayb.soundconnect.modules.studio.migration;

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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Executes the actual forward migration only against a disposable PostgreSQL. */
@Testcontainers
class StudioReservationNotificationOutboxMigrationPostgresTest {
    private static final String TABLE = "tbl_studio_reservation_notification_outbox";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("studio_reservation_outbox_migration")
            .withUsername("studio_test").withPassword("studio_test").withReuse(false);

    @BeforeEach
    void resetSchema() throws SQLException {
        execute("DROP TABLE IF EXISTS " + TABLE + " CASCADE");
    }

    @Test
    void freshMigrationIsRerunnableAndPreservesEverySupportedActionAndEventIdentity() throws Exception {
        execute(migrationSql());
        for (String[] mapping : mappings()) {
            execute(insert(UUID.randomUUID(), mapping[0], mapping[1]));
        }
        execute(migrationSql());

        assertThat(count("SELECT count(*) FROM " + TABLE)).isEqualTo(8);
        assertThat(count("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema=current_schema() AND table_name='tbl_studio_reservation_notification_outbox'
                """)).isEqualTo(17);
        assertThat(count("""
                SELECT count(*) FROM pg_indexes
                WHERE schemaname=current_schema() AND tablename='tbl_studio_reservation_notification_outbox'
                  AND indexname IN ('idx_studio_reservation_notification_outbox_due',
                    'idx_studio_reservation_notification_outbox_lease',
                    'idx_studio_reservation_notification_outbox_created',
                    'idx_studio_reservation_notification_outbox_published')
                """)).isEqualTo(4);

        UUID eventId = UUID.randomUUID();
        execute(insert(eventId, "CREATED", "CREATED"));
        assertSqlState(() -> execute(insert(eventId, "CREATED", "CREATED")), "23505");
        execute(insert(eventId, "CREATED", "CREATED") + " ON CONFLICT(event_id) DO NOTHING");
        assertThat(count("SELECT count(*) FROM " + TABLE + " WHERE event_id='" + eventId + "'")).isEqualTo(1);
    }

    @Test
    void reconcilesAllChecksOnAnExistingHibernateShapedTable() throws Exception {
        execute(bootstrapSql());
        execute(insert(UUID.randomUUID(), "REJECTED", "AUTO_REJECTED_CONFLICT"));
        execute(migrationSql());
        execute(migrationSql());

        assertThat(count("SELECT count(*) FROM pg_constraint WHERE conrelid='" + TABLE
                + "'::regclass AND contype='c'")).isEqualTo(11);
        assertThat(count("SELECT count(*) FROM " + TABLE)).isEqualTo(1);
        assertRejected("status='BROKEN'");
        assertRejected("email_force=true");
    }

    @Test
    void rejectsUnknownTypesMissingModuleAndMismatchedOrMissingActions() throws Exception {
        execute(migrationSql());
        execute(insert(UUID.randomUUID(), "CREATED", "CREATED"));

        for (String invalid : List.of(
                "notification_type='SOCIAL_NEW_FOLLOWER'",
                "payload=payload-'action'",
                "payload=payload-'module'",
                "payload=jsonb_set(payload,'{module}','\"COLLAB\"')",
                "payload=jsonb_set(payload,'{action}','\"APPROVED\"')",
                "payload=jsonb_set(payload,'{action}','null')",
                "payload='[]'::jsonb",
                "email_force=true")) {
            assertRejected(invalid);
        }
        assertThat(count("SELECT count(*) FROM " + TABLE)).isEqualTo(1);
    }

    @Test
    void enforcesStateLeaseAndStorageInvariants() throws Exception {
        execute(migrationSql());
        execute(insert(UUID.randomUUID(), "CREATED", "CREATED"));

        for (String invalid : List.of(
                "status='BROKEN'",
                "attempt_count=-1",
                "title=' '",
                "message=' '",
                "last_error_type=' '",
                "lease_owner='orphan'",
                "lease_until=now()",
                "status='IN_FLIGHT'",
                "status='IN_FLIGHT',lease_owner=' ',lease_until=now()+interval '30 seconds'",
                "status='PUBLISHED'",
                "published_at=now()")) {
            assertRejected(invalid);
        }
        assertThat(count("SELECT count(*) FROM " + TABLE + " WHERE status='PENDING' AND attempt_count=0"))
                .isEqualTo(1);
    }

    @Test
    void permitsLeaseRecoveryRetryAndPublishedCleanupShape() throws Exception {
        execute(migrationSql());
        execute(insert(UUID.randomUUID(), "CANCELLED_BY_STUDIO", "CANCELLED_BY_STUDIO_ROOM_ARCHIVED"));
        execute("UPDATE " + TABLE + " SET status='IN_FLIGHT',attempt_count=1,"
                + "lease_owner='node:claim',lease_until=now()+interval '30 seconds'");
        execute("UPDATE " + TABLE + " SET status='PENDING',lease_owner=null,lease_until=null,"
                + "next_attempt_at=now()+interval '5 seconds',last_error_type='PublishFailure'");
        execute("UPDATE " + TABLE + " SET status='IN_FLIGHT',attempt_count=2,"
                + "lease_owner='node:retry',lease_until=now()+interval '30 seconds'");
        execute("UPDATE " + TABLE + " SET status='PUBLISHED',lease_owner=null,lease_until=null,"
                + "published_at=now(),last_error_type=null");
        execute(migrationSql());

        assertThat(count("SELECT count(*) FROM " + TABLE
                + " WHERE status='PUBLISHED' AND attempt_count=2 AND published_at IS NOT NULL")).isEqualTo(1);
    }

    @Test
    void invalidPreexistingDataAbortsTheWholeMigrationWithoutSilentlyDroppingIt() throws Exception {
        execute(bootstrapSql());
        execute(insert(UUID.randomUUID(), "CREATED", "CREATED"));
        execute("UPDATE " + TABLE + " SET email_force=true");

        assertSqlState(() -> execute(migrationSql()), "23514");

        assertThat(count("SELECT count(*) FROM " + TABLE + " WHERE email_force")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM pg_constraint WHERE conrelid='" + TABLE
                + "'::regclass AND contype='c'")).isZero();
        assertThat(count("SELECT count(*) FROM pg_indexes WHERE schemaname=current_schema() AND tablename='"
                + TABLE + "' AND indexname LIKE 'idx_studio%'")).isZero();
    }

    private static List<String[]> mappings() {
        return List.of(
                new String[]{"CREATED", "CREATED"},
                new String[]{"CONFLICTING_REQUESTS", "CONFLICTING_REQUESTS"},
                new String[]{"APPROVED", "APPROVED"},
                new String[]{"REJECTED", "REJECTED"},
                new String[]{"REJECTED", "AUTO_REJECTED_CONFLICT"},
                new String[]{"CANCELLED_BY_CUSTOMER", "CANCELLED_BY_CUSTOMER"},
                new String[]{"CANCELLED_BY_STUDIO", "CANCELLED_BY_STUDIO"},
                new String[]{"CANCELLED_BY_STUDIO", "CANCELLED_BY_STUDIO_ROOM_ARCHIVED"});
    }

    private static void assertRejected(String assignments) {
        assertSqlState(() -> execute("UPDATE " + TABLE + " SET " + assignments), "23514");
    }

    private static void assertSqlState(SqlWork work, String expected) {
        assertThatThrownBy(work::run).isInstanceOf(SQLException.class)
                .satisfies(error -> assertThat(((SQLException) error).getSQLState()).isEqualTo(expected));
    }

    private static String insert(UUID eventId, String type, String action) {
        return """
                INSERT INTO tbl_studio_reservation_notification_outbox(
                    event_id,recipient_id,notification_type,title,message,payload,email_force,occurred_at,
                    status,attempt_count,next_attempt_at,created_at,updated_at)
                VALUES ('%s','00000000-0000-0000-0000-000000000110','STUDIO_RESERVATION_%s',
                    'Title','Message','{"module":"STUDIO","action":"%s"}',false,now(),
                    'PENDING',0,now(),now(),now())
                """.formatted(eventId, type, action).strip();
    }

    private static String bootstrapSql() {
        return """
                CREATE TABLE tbl_studio_reservation_notification_outbox(
                    event_id uuid PRIMARY KEY, recipient_id uuid NOT NULL,
                    notification_type varchar(64) NOT NULL, title varchar(160) NOT NULL,
                    message varchar(1000) NOT NULL, payload jsonb NOT NULL, email_force boolean NOT NULL,
                    occurred_at timestamptz NOT NULL, status varchar(24) NOT NULL,
                    attempt_count integer NOT NULL, next_attempt_at timestamptz NOT NULL,
                    lease_owner varchar(100), lease_until timestamptz, last_error_type varchar(200),
                    published_at timestamptz, created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL)
                """;
    }

    private static String migrationSql() throws Exception {
        return Files.readString(Path.of("scripts", "db", "2026-09-24-studio-reservation-notification-outbox.sql"));
    }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static int count(String sql) throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getInt(1);
        }
    }

    private static Connection connection() throws SQLException {
        if (!POSTGRES.isRunning()) throw new IllegalStateException("Disposable PostgreSQL must be running");
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    @FunctionalInterface
    private interface SqlWork { void run() throws Exception; }
}

