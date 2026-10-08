package com.berkayb.soundconnect.modules.admin.health;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.berkayb.soundconnect.modules.admin.health.SystemHealthSnapshot.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class MobileDiagnosticsStorePostgresTest {
    @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("stage01_mobile_diagnostics").withLabel("soundconnect.fixture", "stage01-mobile-diagnostics").withReuse(false);
    JdbcTemplate jdbc;
    MobileDiagnosticsStore store;
    MobileDiagnosticsProperties properties;
    DataSourceTransactionManager manager;
    UUID actor;

    @BeforeEach void setup() throws Exception {
        assertThat(postgres.getContainerInfo().getConfig().getLabels()).containsEntry("soundconnect.fixture", "stage01-mobile-diagnostics");
        var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(source); manager = new DataSourceTransactionManager(source);
        jdbc.execute(Files.readString(Path.of("scripts/db/2026-10-08-mobile-diagnostics.sql")));
        jdbc.execute("CREATE TABLE IF NOT EXISTS tbl_user(id uuid PRIMARY KEY, session_version bigint NOT NULL, status varchar(32), email_verified boolean, erased_at timestamptz)");
        jdbc.update("DELETE FROM tbl_mobile_diagnostic_event"); jdbc.update("DELETE FROM tbl_user");
        actor = UUID.randomUUID(); jdbc.update("INSERT INTO tbl_user VALUES(?,7,'ACTIVE',true,NULL)", actor);
        properties = new MobileDiagnosticsProperties();
        store = new MobileDiagnosticsStore(source, manager, properties, new ObjectMapper(), new MockEnvironment().withProperty("spring.profiles.active", "test"));
    }
    @AfterEach void close() { if (store != null) store.close(); }

    @Test void idempotencyStoresOneAnonymousEventAndDetectsChangedPayload() {
        var request = event(UUID.randomUUID(), MobileDiagnosticRequest.Severity.ERROR);
        assertThat(store.record(actor, 7, request).accepted()).isTrue();
        assertThat(store.record(actor, 7, request).accepted()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tbl_mobile_diagnostic_event", Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> store.record(actor, 7, event(request.eventId(), MobileDiagnosticRequest.Severity.FATAL)))
                .isInstanceOfSatisfying(MobileDiagnosticsStore.Rejected.class, failure -> assertThat(failure.reason).isEqualTo(MobileDiagnosticsStore.Rejection.CONFLICT));
        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_name='tbl_mobile_diagnostic_event'", String.class))
                .containsExactlyInAnyOrder("event_id", "fingerprint", "severity", "source", "error_type", "frames", "environment", "received_at");
    }

    @Test void revokedSessionInactiveOrMissingAccountCannotPersist() {
        var request = event(UUID.randomUUID(), MobileDiagnosticRequest.Severity.ERROR);
        assertThatThrownBy(() -> store.record(actor, 6, request)).isInstanceOf(MobileDiagnosticsStore.Rejected.class);
        assertThatThrownBy(() -> store.record(UUID.randomUUID(), 7, request)).isInstanceOf(MobileDiagnosticsStore.Rejected.class);
        jdbc.update("UPDATE tbl_user SET email_verified=false WHERE id=?", actor);
        assertThatThrownBy(() -> store.record(actor, 7, request)).isInstanceOf(MobileDiagnosticsStore.Rejected.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tbl_mobile_diagnostic_event", Integer.class)).isZero();
    }

    @Test void passwordResetCommitWinsAgainstDelayedOldSessionRecord() throws Exception {
        CountDownLatch changed = new CountDownLatch(1), commit = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var reset = executor.submit(() -> new TransactionTemplate(manager).execute(ignored -> {
                jdbc.update("UPDATE tbl_user SET session_version=8 WHERE id=?", actor);
                changed.countDown();
                try { if (!commit.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("fixture commit timeout"); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(); }
                return true;
            }));
            assertThat(changed.await(2, TimeUnit.SECONDS)).isTrue();
            var late = executor.submit(() -> store.record(actor, 7, event(UUID.randomUUID(), MobileDiagnosticRequest.Severity.ERROR)));
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(1)).until(() ->
                    Boolean.TRUE.equals(jdbc.queryForObject("""
                            SELECT exists(SELECT 1 FROM pg_stat_activity WHERE datname=current_database()
                            AND wait_event_type='Lock' AND query LIKE 'SELECT session_version FROM tbl_user%')
                            """, Boolean.class)));
            assertThat(late.isDone()).isFalse();
            commit.countDown(); reset.get(3, TimeUnit.SECONDS);
            assertThatThrownBy(() -> late.get(3, TimeUnit.SECONDS)).hasCauseInstanceOf(MobileDiagnosticsStore.Rejected.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM tbl_mobile_diagnostic_event", Integer.class)).isZero();
        } finally { commit.countDown(); }
    }

    @Test void rowCapDoesNotGrowOnNewIdsAndDuplicateStillReturnsSameReceipt() {
        properties.setMaxEvents(2);
        var first = event(UUID.randomUUID(), MobileDiagnosticRequest.Severity.ERROR);
        store.record(actor, 7, first);
        store.record(actor, 7, event(UUID.randomUUID(), MobileDiagnosticRequest.Severity.ERROR));
        assertThat(store.record(actor, 7, first).eventId()).isEqualTo(first.eventId());
        assertThatThrownBy(() -> store.record(actor, 7, event(UUID.randomUUID(), MobileDiagnosticRequest.Severity.ERROR)))
                .isInstanceOfSatisfying(MobileDiagnosticsStore.Rejected.class, failure -> assertThat(failure.reason).isEqualTo(MobileDiagnosticsStore.Rejection.CAPACITY));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tbl_mobile_diagnostic_event", Integer.class)).isEqualTo(2);
    }

    @Test void healthShowsCurrentWindowAndRetentionDeletesOnlyBoundedOldDiagnosticRows() {
        store.record(actor, 7, event(UUID.randomUUID(), MobileDiagnosticRequest.Severity.FATAL));
        var health = store.health();
        assertThat(health.status()).isEqualTo(Status.DEGRADED);
        assertThat(health.metrics()).containsEntry("eventsLast15Minutes", 1d).containsEntry("fatalEventsLast15Minutes", 1d);
        jdbc.update("UPDATE tbl_mobile_diagnostic_event SET received_at=CURRENT_TIMESTAMP-INTERVAL '20 minutes'");
        assertThat(store.health().status()).isEqualTo(Status.UP);
        jdbc.update("""
                INSERT INTO tbl_mobile_diagnostic_event(event_id,fingerprint,severity,source,error_type,frames,environment,received_at)
                SELECT gen_random_uuid(),repeat('a',64),'ERROR','DIAGNOSTICS_CHECK','DiagnosticAcceptanceCheck','','local',CURRENT_TIMESTAMP-INTERVAL '8 days'
                FROM generate_series(1,1201)
                """);
        store.cleanup();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tbl_mobile_diagnostic_event", Integer.class)).isEqualTo(202);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tbl_user", Integer.class)).isEqualTo(1);
        assertThat(store.recent(10)).hasSize(10).allSatisfy(event -> assertThat(event.environment()).isEqualTo(MobileDiagnosticRequest.Environment.local));
        assertThatThrownBy(() -> store.recent(11)).isInstanceOf(IllegalArgumentException.class);
    }
    private static MobileDiagnosticRequest event(UUID id, MobileDiagnosticRequest.Severity severity) {
        return new MobileDiagnosticRequest(id, severity, MobileDiagnosticRequest.Source.DIAGNOSTICS_CHECK,
                MobileDiagnosticRequest.ErrorType.DiagnosticAcceptanceCheck, List.of("dart:async/future_impl.dart:15:2"), MobileDiagnosticRequest.Environment.local);
    }
}
