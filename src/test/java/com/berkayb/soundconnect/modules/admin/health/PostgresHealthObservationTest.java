package com.berkayb.soundconnect.modules.admin.health;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.berkayb.soundconnect.modules.admin.health.SystemHealthSnapshot.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Timeout(15)
class PostgresHealthObservationTest {
    static final String EMPTY_SESSIONS = """
            {"sessions":0,"active":0,"idleInTransaction":0,"slowActive":0,"oldestActiveSeconds":0,
             "waitingLocks":0,"waitStartKnown":0,"oldestKnownWaitSeconds":0,
             "databaseDeadlocks":0,"databaseStatsResetKnown":0,"databaseStatsResetEpoch":null,"databasePostmasterStartEpoch":0.5}
            """;
    static final String EMPTY_STATEMENTS = """
            {"statementGroups":0,"calls":0,"totalExecutionMs":0,"maximumExecutionMs":null,
             "groupsWithMeanOverOneSecond":0,"blocksRead":0,"blocksHit":0,"deallocatedGroups":0,"statsResetEpoch":null}
            """;
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-12T01:00:00Z"), ZoneOffset.UTC);

    @Test void bothDefaultFlagsAreDisabledWithoutBorrowingOrCreatingThreads() {
        DataSource source = mock(DataSource.class);
        var reader = new PostgresHealthObservation(source, new SystemHealthProperties(), CLOCK);
        assertThat(reader.sessions().status()).isEqualTo(Status.DISABLED);
        assertThat(reader.statements().status()).isEqualTo(Status.DISABLED);
        verifyNoInteractions(source);
    }

    @Test void successfulBorrowUsesReadOnlyLocalTimeoutsRollbackAndRestoresBeforeReturningPoolConnection() throws Exception {
        var f = fixture(EMPTY_SESSIONS);
        var result = f.reader.sessions();
        assertThat(result.status()).isEqualTo(Status.UP);
        assertThat(result.metrics()).hasSize(11).containsEntry("sessions", 0d)
                .containsEntry("databaseDeadlocks", 0d).containsEntry("databaseStatsResetKnown", 0d)
                .doesNotContainKey("databaseStatsResetEpoch");
        var order = inOrder(f.connection, f.statement, f.rows);
        order.verify(f.connection).setNetworkTimeout(any(), intThat(n -> n > 0 && n <= 1000));
        order.verify(f.connection).setReadOnly(true);
        order.verify(f.connection).setAutoCommit(false);
        order.verify(f.statement).execute(argThat(sql -> sql.contains("SET LOCAL statement_timeout=")
                && sql.contains("SET LOCAL lock_timeout=") && sql.contains("idle_in_transaction_session_timeout='5000ms'")
                && sql.contains("search_path=pg_catalog,pg_temp")));
        order.verify(f.statement).executeQuery(PostgresHealthObservation.SESSIONS_SQL);
        order.verify(f.rows).close();
        order.verify(f.statement).close();
        order.verify(f.connection).rollback();
        order.verify(f.connection).setReadOnly(false);
        order.verify(f.connection).setAutoCommit(true);
        order.verify(f.connection).setNetworkTimeout(any(), eq(0));
        order.verify(f.connection).close();
        verify(f.connection, never()).commit();
        verify(f.connection, never()).abort(any());
        assertThat(PostgresHealthObservation.SESSIONS_SQL).contains("usename=current_user", "datname=current_database()", "pid<>pg_backend_pid()");
    }

    @Test void databaseCounterAndResetAreMeasuredByTheSameBoundedSelectWithoutInventingMissingReset() throws Exception {
        String measured = EMPTY_SESSIONS.replace("\"databaseDeadlocks\":0", "\"databaseDeadlocks\":17")
                .replace("\"databaseStatsResetKnown\":0", "\"databaseStatsResetKnown\":1")
                .replace("\"databaseStatsResetEpoch\":null", "\"databaseStatsResetEpoch\":1791766799.125");
        var f = fixture(measured);
        var reading = f.reader.sessions();
        assertThat(reading.status()).isEqualTo(Status.UP);
        assertThat(reading.metrics()).hasSize(12).containsEntry("databaseDeadlocks", 17d)
                .containsEntry("databaseStatsResetKnown", 1d).containsEntry("databaseStatsResetEpoch", 1791766799.125d);
        verify(f.statement, times(1)).executeQuery(PostgresHealthObservation.SESSIONS_SQL);
        verify(f.connection, times(1)).createStatement();
        assertThat(PostgresHealthObservation.SESSIONS_SQL).contains("FROM pg_catalog.pg_stat_database",
                "WHERE datname=current_database()", "deadlocks IS NULL", "extract(epoch FROM stats_reset)")
                .doesNotContain("coalesce(deadlocks", "pg_stat_reset", "pg_stat_clear_snapshot");
        assertThat(PostgresHealthObservation.parse(EMPTY_SESSIONS, false, 0))
                .containsEntry("databaseStatsResetKnown", 0d).doesNotContainKey("databaseStatsResetEpoch");
    }

    @Test void nullableResetKeepsActualServerStartInTheSameSelectAndMissingIsNotNull() throws Exception {
        String raw = EMPTY_SESSIONS.replace("\"databasePostmasterStartEpoch\":0.5",
                "\"databasePostmasterStartEpoch\":1791632504.392923");
        var f = fixture(raw);
        var reading = f.reader.sessions();
        assertThat(reading.status()).isEqualTo(Status.UP);
        assertThat(reading.metrics()).containsEntry("databaseStatsResetKnown", 0d)
                .containsEntry("databasePostmasterStartEpoch", 1791632504.392923d)
                .doesNotContainKey("databaseStatsResetEpoch");
        verify(f.statement, times(1)).executeQuery(PostgresHealthObservation.SESSIONS_SQL);
        assertThat(PostgresHealthObservation.SESSIONS_SQL)
                .contains("extract(epoch FROM pg_catalog.pg_postmaster_start_time())")
                .doesNotContain("coalesce(stats_reset", "coalesce(deadlocks", "pg_stat_reset", "pg_stat_clear_snapshot");
        for (String changed : List.of(raw.replace(",\"databasePostmasterStartEpoch\":1791632504.392923", ""),
                raw.replace("1791632504.392923", "null"), raw.replace("1791632504.392923", "0"),
                raw.replace("1791632504.392923", "-1"), raw.replace("1791632504.392923", "true"),
                raw.replace("1791632504.392923", "9999999999"),
                raw.replace("\"databaseStatsResetEpoch\":null", "\"databaseStatsResetEpoch\":0"))) {
            assertThatThrownBy(() -> PostgresHealthObservation.parse(changed, false,
                    CLOCK.instant().getEpochSecond())).isInstanceOf(RuntimeException.class);
        }
    }

    @Test void missingInvalidOrIncoherentDatabaseCountersStayUnknownWithoutPrivateValues() throws Exception {
        List<String> invalid = List.of(
                EMPTY_SESSIONS.replace("\"databaseDeadlocks\":0,", ""),
                EMPTY_SESSIONS.replace("\"databaseDeadlocks\":0", "\"databaseDeadlocks\":null"),
                EMPTY_SESSIONS.replace("\"databaseDeadlocks\":0", "\"databaseDeadlocks\":\"private-query-token\""),
                EMPTY_SESSIONS.replace("\"databaseDeadlocks\":0", "\"databaseDeadlocks\":-1"),
                EMPTY_SESSIONS.replace("\"databaseDeadlocks\":0", "\"databaseDeadlocks\":0.5"),
                EMPTY_SESSIONS.replace("\"databaseDeadlocks\":0", "\"databaseDeadlocks\":9007199254740992"),
                EMPTY_SESSIONS.replace("\"databaseDeadlocks\":0", "\"databaseDeadlocks\":0,\"databaseDeadlocks\":1"),
                EMPTY_SESSIONS.replace("\"databaseDeadlocks\":0", "\"databaseDeadlocks\":0,\"databaseName\":\"private-db\""),
                EMPTY_SESSIONS.replace("\"databaseStatsResetKnown\":0", "\"databaseStatsResetKnown\":null"),
                EMPTY_SESSIONS.replace("\"databaseStatsResetKnown\":0", "\"databaseStatsResetKnown\":2"),
                EMPTY_SESSIONS.replace("\"databaseStatsResetKnown\":0", "\"databaseStatsResetKnown\":true"),
                EMPTY_SESSIONS.replace("\"databaseStatsResetKnown\":0", "\"databaseStatsResetKnown\":1"),
                EMPTY_SESSIONS.replace("\"databaseStatsResetEpoch\":null", "\"databaseStatsResetEpoch\":1"),
                EMPTY_SESSIONS.replace("\"databaseStatsResetKnown\":0", "\"databaseStatsResetKnown\":1")
                        .replace("\"databaseStatsResetEpoch\":null", "\"databaseStatsResetEpoch\":9999999999"));
        for (String raw : invalid) {
            var f = fixture(raw);
            var reading = f.reader.sessions();
            assertThat(reading.status()).isEqualTo(Status.UNKNOWN);
            assertThat(reading.reasonCode()).isEqualTo(ReasonCode.INVALID_MEASUREMENT);
            assertThat(reading.metrics()).isEmpty();
            assertThat(reading.toString()).doesNotContain("private-query-token", "private-db");
            verify(f.connection).rollback();
            verify(f.connection).close();
        }
    }

    @Test void statementWrapperIsTheOnlyStatisticsCallAndNullableMaximumAndResetRemainAbsent() throws Exception {
        var f = fixture(EMPTY_STATEMENTS);
        var reading = f.reader.statements();
        assertThat(reading.status()).isEqualTo(Status.UP);
        assertThat(reading.metrics()).hasSize(7).doesNotContainKeys("maximumExecutionMs", "statsResetEpoch");
        verify(f.statement).executeQuery("SELECT left(public.soundconnect_db_statement_snapshot_v1()::text,2049)");
        verify(f.statement, never()).executeQuery(contains("pg_stat_statements("));
    }

    @Test void wrapperShapeNumericBoundsAndTrailingDataFailClosedWithoutPrivateDetails() {
        List<String> invalid = List.of(
                EMPTY_STATEMENTS.replace("\"calls\":0", "\"calls\":\"secret-query-token\""),
                EMPTY_STATEMENTS.replace("\"calls\":0", "\"calls\":-1"),
                EMPTY_STATEMENTS.replace("\"calls\":0", "\"calls\":0.5"),
                EMPTY_STATEMENTS.replace("\"calls\":0", "\"calls\":9007199254740992"),
                EMPTY_STATEMENTS.replace("\"calls\":0", "\"calls\":0,\"calls\":2"),
                EMPTY_STATEMENTS.replace("\"calls\":0", "\"calls\":0,\"query\":\"secret-sql\""),
                EMPTY_STATEMENTS.replace("\"calls\":0,", ""),
                EMPTY_STATEMENTS.replace("\"maximumExecutionMs\":null", "\"maximumExecutionMs\":0"),
                EMPTY_STATEMENTS.replace("\"statsResetEpoch\":null", "\"statsResetEpoch\":9999999999"),
                EMPTY_STATEMENTS + " {}", "[1,2]", " ".repeat(2049));
        for (String raw : invalid)
            assertThatThrownBy(() -> PostgresHealthObservation.parse(raw, true, CLOCK.millis() / 1000d))
                    .isInstanceOf(RuntimeException.class).hasMessage(null);
    }

    @Test void knownZeroDiffersFromUnavailableLockAgeAndInconsistentAggregatesAreRejected() {
        String partial = EMPTY_SESSIONS.replace("\"waitingLocks\":0", "\"waitingLocks\":1")
                .replace("\"oldestKnownWaitSeconds\":0", "\"oldestKnownWaitSeconds\":null");
        assertThat(PostgresHealthObservation.parse(partial, false, 0)).containsEntry("waitingLocks", 1d)
                .doesNotContainKey("oldestKnownWaitSeconds");
        for (String raw : List.of(EMPTY_SESSIONS.replace("\"active\":0", "\"active\":1"),
                EMPTY_SESSIONS.replace("\"slowActive\":0", "\"slowActive\":1"),
                EMPTY_SESSIONS.replace("\"waitStartKnown\":0", "\"waitStartKnown\":1"),
                EMPTY_SESSIONS.replace("\"oldestActiveSeconds\":0", "\"oldestActiveSeconds\":10"),
                EMPTY_SESSIONS.replace("\"oldestKnownWaitSeconds\":0", "\"oldestKnownWaitSeconds\":null")))
            assertThatThrownBy(() -> PostgresHealthObservation.parse(raw, false, 0)).isInstanceOf(RuntimeException.class);
    }

    @Test void queryFailureDiscardsAllValuesAndRollsBackWithoutLoggingOrReusingRawException() throws Exception {
        var f = fixture(EMPTY_SESSIONS);
        when(f.statement.executeQuery(anyString())).thenThrow(new SQLException("secret-password/query/customer-id"));
        var result = f.reader.sessions();
        assertThat(result.status()).isEqualTo(Status.UNKNOWN);
        assertThat(result.metrics()).isEmpty();
        assertThat(result.toString()).doesNotContain("secret", "customer", "query");
        verify(f.connection).rollback(); verify(f.connection).close();
    }

    @Test void rollbackFailureAbortsAndDoesNotToggleAutoCommitOrPublishHealthy() throws Exception {
        var f = fixture(EMPTY_SESSIONS);
        doThrow(new SQLException("secret rollback response")).when(f.connection).rollback();
        assertThat(f.reader.sessions().status()).isEqualTo(Status.UNKNOWN);
        verify(f.connection).abort(any());
        verify(f.connection, never()).setAutoCommit(true);
        verify(f.connection).close();
    }

    @Test void restoreOrCloseFailureAbortsAndCannotPublishHealthy() throws Exception {
        var f = fixture(EMPTY_SESSIONS);
        doThrow(new SQLException("private")).when(f.connection).setReadOnly(false);
        assertThat(f.reader.sessions().status()).isEqualTo(Status.UNKNOWN);
        verify(f.connection).abort(any()); verify(f.connection).close();
        var closed = fixture(EMPTY_SESSIONS);
        doThrow(new SQLException("private")).when(closed.connection).close();
        assertThat(closed.reader.sessions().status()).isEqualTo(Status.UNKNOWN);
        verify(closed.connection).abort(any());
    }

    @Test void unexpectedTransactionOrUnavailableNetworkTimeoutDoesNotRunSelect() throws Exception {
        var f = fixture(EMPTY_SESSIONS);
        when(f.connection.getAutoCommit()).thenReturn(false);
        assertThat(f.reader.sessions().status()).isEqualTo(Status.UNKNOWN);
        verify(f.connection, never()).createStatement(); verify(f.connection).close();
        var unsupported = fixture(EMPTY_SESSIONS);
        doThrow(new SQLException("unsupported")).when(unsupported.connection).setNetworkTimeout(any(), anyInt());
        assertThat(unsupported.reader.sessions().status()).isEqualTo(Status.UNKNOWN);
        verify(unsupported.connection, never()).createStatement(); verify(unsupported.connection).close();
    }

    @Test void extraRowsOrOversizedWrapperAreUnknownAndConnectionStillCloses() throws Exception {
        var f = fixture(EMPTY_STATEMENTS + " ".repeat(2049));
        assertThat(f.reader.statements().reasonCode()).isEqualTo(ReasonCode.INVALID_MEASUREMENT);
        verify(f.connection).rollback(); verify(f.connection).close();
        var two = fixture(EMPTY_SESSIONS);
        when(two.rows.next()).thenReturn(true, true, false);
        assertThat(two.reader.sessions().status()).isEqualTo(Status.UNKNOWN);
        verify(two.connection).rollback(); verify(two.connection).close();
    }

    @Test void slowExistingPoolBorrowKeepsSingleBusySlotAndLateConnectionIsClosedWithoutRunningSql() throws Exception {
        var f = fixture(EMPTY_SESSIONS);
        f.properties.setProbeTimeoutMillis(100);
        CountDownLatch release = new CountDownLatch(1), closed = new CountDownLatch(1);
        AtomicInteger borrows = new AtomicInteger();
        when(f.source.getConnection()).thenAnswer(invocation -> {
            borrows.incrementAndGet();
            while (release.getCount() > 0) {
                try { release.await(); } catch (InterruptedException ignored) { /* Borrower ignores cancellation. */ }
            }
            return f.connection;
        });
        doAnswer(invocation -> { closed.countDown(); return null; }).when(f.connection).close();
        try (var service = new SystemHealthService(List.of(f.reader.sessionsProbe()), f.properties, CLOCK, false)) {
            service.refresh();
            assertThat(service.snapshot().components().getFirst().reasonCode()).isEqualTo(ReasonCode.PROBE_TIMEOUT);
            for (int i = 0; i < 8; i++) service.refresh();
            assertThat(borrows).hasValue(1);
            assertThat(service.snapshot().components().getFirst().reasonCode()).isEqualTo(ReasonCode.PROBE_BUSY);
            release.countDown();
            assertThat(closed.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(service.snapshot().components().getFirst().measuredAt()).isNull();
            verify(f.connection, never()).createStatement();
        } finally { release.countDown(); }
    }

    static Fixture fixture(String json) throws Exception {
        DataSource source = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet rows = mock(ResultSet.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(rows);
        when(rows.next()).thenReturn(true, false);
        when(rows.getString(1)).thenReturn(json);
        var properties = new SystemHealthProperties();
        properties.setPostgresSessionsEnabled(true); properties.setPostgresStatementsEnabled(true);
        return new Fixture(source, connection, statement, rows, properties, new PostgresHealthObservation(source, properties, CLOCK));
    }

    record Fixture(DataSource source, Connection connection, Statement statement, ResultSet rows,
                   SystemHealthProperties properties, PostgresHealthObservation reader) { }
}
