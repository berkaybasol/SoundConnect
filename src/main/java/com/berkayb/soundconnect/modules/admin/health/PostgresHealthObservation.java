package com.berkayb.soundconnect.modules.admin.health;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.time.Clock;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static com.berkayb.soundconnect.modules.admin.health.SystemHealthProbe.Reading;
import static com.berkayb.soundconnect.modules.admin.health.SystemHealthSnapshot.*;

/**
 * Fixed, aggregate-only SELECTs through the application's existing pool. Only the
 * bounded SystemHealthService probe executor calls this reader; never a request,
 * readiness, lease, meter-read or metrics-publisher thread.
 */
public final class PostgresHealthObservation {
    static final int MAX_JSON_CHARS = 2048;
    private static final BigDecimal MAX_NUMBER = new BigDecimal("9007199254740991");
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(2)
                    .maxStringLength(MAX_JSON_CHARS).maxNumberLength(32).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    // Eight own-role aggregates plus four current-database/server continuity
    // fields. No database/role/PID/query/lock identity is returned. A NULL reset
    // timestamp is represented as known=0 and absent epoch, never a fake epoch.
    static final String SESSIONS_SQL = """
            WITH own AS MATERIALIZED (
                SELECT pid,state,query_start FROM pg_catalog.pg_stat_activity
                WHERE datname=current_database() AND usename=current_user AND pid<>pg_backend_pid()
            ), waits AS MATERIALIZED (
                SELECT l.waitstart FROM pg_catalog.pg_locks l JOIN own o ON o.pid=l.pid
                WHERE NOT l.granted
            ), database_counters AS MATERIALIZED (
                SELECT deadlocks,stats_reset FROM pg_catalog.pg_stat_database
                WHERE datname=current_database()
            )
            SELECT CASE WHEN current_setting('track_activities')<>'on'
                         OR (SELECT count(*) FROM database_counters)<>1
                         OR EXISTS(SELECT 1 FROM database_counters WHERE deadlocks IS NULL)
                         OR EXISTS(SELECT 1 FROM own WHERE state IS NULL OR state='disabled'
                                     OR (state='active' AND query_start IS NULL)) THEN NULL
            ELSE pg_catalog.jsonb_build_object(
                'sessions',(SELECT count(*) FROM own),
                'active',(SELECT count(*) FROM own WHERE state='active'),
                'idleInTransaction',(SELECT count(*) FROM own WHERE state IN ('idle in transaction','idle in transaction (aborted)')),
                'slowActive',(SELECT count(*) FROM own WHERE state='active' AND query_start<=statement_timestamp()-interval '1 second'),
                'oldestActiveSeconds',(SELECT coalesce(max(greatest(0,extract(epoch FROM statement_timestamp()-query_start)))
                                             FILTER(WHERE state='active'),0) FROM own),
                'waitingLocks',(SELECT count(*) FROM waits),
                'waitStartKnown',(SELECT count(waitstart) FROM waits),
                'oldestKnownWaitSeconds',(SELECT CASE WHEN count(*)=0 THEN 0
                                               ELSE max(greatest(0,extract(epoch FROM statement_timestamp()-waitstart)))
                                                    FILTER(WHERE waitstart IS NOT NULL) END FROM waits),
                'databaseDeadlocks',(SELECT deadlocks FROM database_counters),
                'databaseStatsResetKnown',(SELECT CASE WHEN stats_reset IS NULL THEN 0 ELSE 1 END FROM database_counters),
                'databaseStatsResetEpoch',(SELECT extract(epoch FROM stats_reset) FROM database_counters),
                'databasePostmasterStartEpoch',extract(epoch FROM pg_catalog.pg_postmaster_start_time())
            )::text END
            """;
    // The separately approved wrapper returns nine numeric fields, never native rows.
    // Bound the returned value at the server as well as in the strict Java parser.
    static final String STATEMENTS_SQL = "SELECT left(public.soundconnect_db_statement_snapshot_v1()::text,2049)";

    private final DataSource dataSource;
    private final SystemHealthProperties properties;
    private final Clock clock;

    public PostgresHealthObservation(DataSource dataSource, SystemHealthProperties properties, Clock clock) {
        this.dataSource = dataSource;
        this.properties = properties;
        this.clock = clock;
    }

    SystemHealthProbe sessionsProbe() {
        return new SystemHealthProbe(PostgresHealthMetrics.SESSIONS, "DB rol oturumları ve DB sayaçları",
                "Oturum/lock alanları yalnız bu uygulamanın DB rolü; databaseDeadlocks/reset alanları mevcut DB genelidir. Tüm DB sağlığı kabulü değildir.", this::sessions);
    }

    SystemHealthProbe statementsProbe() {
        return new SystemHealthProbe(PostgresHealthMetrics.STATEMENTS, "DB sorgu istatistikleri",
                "Sabit sayısal wrapper; yedi alan mevcut DB, deallocation/reset server genelidir. Ham sorgu yoktur.", this::statements);
    }

    Reading sessions() { return read(properties.isPostgresSessionsEnabled(), false); }
    Reading statements() { return read(properties.isPostgresStatementsEnabled(), true); }

    private Reading read(boolean enabled, boolean statements) {
        if (!enabled) return new Reading(Status.DISABLED, ReasonCode.FEATURE_DISABLED, clock.instant(), Map.of());
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(properties.getProbeTimeoutMillis());
        Connection connection = null;
        boolean transactionStarted = false;
        boolean stateCaptured = false;
        boolean originalReadOnly = false;
        int originalNetworkTimeout = 0;
        Map<String, Double> values = null;
        ReasonCode failure = ReasonCode.PROBE_FAILED;
        boolean cleanupOk = true;
        try {
            // The existing pool's acquisition timeout is unchanged (currently 30 s).
            // A health deadline is not a hard getConnection deadline. SystemHealthService
            // marks a timed-out probe UNKNOWN and retains its busy slot until it returns.
            connection = dataSource.getConnection();
            remainingMillis(deadline);
            if (!connection.getAutoCommit()) throw new SQLException("Unexpected borrowed transaction state");
            originalReadOnly = connection.isReadOnly();
            originalNetworkTimeout = connection.getNetworkTimeout();
            stateCaptured = true;
            connection.setNetworkTimeout(Runnable::run, Math.min(1000, remainingMillis(deadline)));
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            transactionStarted = true;
            try (Statement statement = connection.createStatement()) {
                statement.setMaxRows(2);
                statement.setFetchSize(1);
                statement.setQueryTimeout(Math.max(1, (remainingMillis(deadline) + 999) / 1000));
                int queryMillis = Math.min(1000, remainingMillis(deadline));
                int lockMillis = Math.min(250, queryMillis);
                statement.execute("SET LOCAL statement_timeout='" + queryMillis + "ms'; "
                        + "SET LOCAL lock_timeout='" + lockMillis + "ms'; "
                        + "SET LOCAL idle_in_transaction_session_timeout='5000ms'; "
                        + "SET LOCAL search_path=pg_catalog,pg_temp");
                remainingMillis(deadline);
                try (ResultSet result = statement.executeQuery(statements ? STATEMENTS_SQL : SESSIONS_SQL)) {
                    if (!result.next()) throw new InvalidMeasurement();
                    values = parse(result.getString(1), statements, clock.millis() / 1000d);
                    if (result.next()) throw new InvalidMeasurement();
                }
                remainingMillis(deadline);
            }
        } catch (InvalidMeasurement invalid) {
            values = null;
            failure = ReasonCode.INVALID_MEASUREMENT;
        } catch (SQLTimeoutException timeout) {
            values = null;
            failure = ReasonCode.PROBE_TIMEOUT;
        } catch (SQLException | RuntimeException unavailable) {
            values = null;
            // Never copy driver exceptions, SQL, connection details or remote text.
        } finally {
            // Cancellation belongs to our one owned probe. Permit cleanup even when
            // the deadline interrupted the borrower, then restore the interrupt flag.
            boolean interrupted = Thread.interrupted();
            if (connection != null) {
                try {
                    if (transactionStarted) connection.rollback();
                    if (stateCaptured) {
                        connection.setReadOnly(originalReadOnly);
                        // Never switch auto-commit back on after a failed rollback.
                        if (transactionStarted) connection.setAutoCommit(true);
                        connection.setNetworkTimeout(Runnable::run, originalNetworkTimeout);
                    }
                } catch (SQLException | RuntimeException cleanupFailure) {
                    cleanupOk = false;
                    try { connection.abort(Runnable::run); } catch (SQLException | RuntimeException ignored) { }
                } finally {
                    try { connection.close(); }
                    catch (SQLException | RuntimeException ignored) {
                        cleanupOk = false;
                        try { connection.abort(Runnable::run); } catch (SQLException | RuntimeException alsoIgnored) { }
                    }
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
        if (System.nanoTime() > deadline || Thread.currentThread().isInterrupted()) {
            values = null;
            failure = ReasonCode.PROBE_TIMEOUT;
        }
        return values == null || !cleanupOk ? Reading.unknown(cleanupOk ? failure : ReasonCode.PROBE_FAILED)
                : new Reading(Status.UP, ReasonCode.HEALTHY, clock.instant(), values);
    }

    private static int remainingMillis(long deadline) throws SQLTimeoutException {
        long remaining = deadline - System.nanoTime();
        if (Thread.currentThread().isInterrupted() || remaining <= 0) throw new SQLTimeoutException("Observation deadline");
        return (int) Math.max(1, Math.min(5000, TimeUnit.NANOSECONDS.toMillis(remaining)));
    }

    static Map<String, Double> parse(String raw, boolean statements, double nowEpoch) {
        if (raw == null || raw.length() > MAX_JSON_CHARS) throw new InvalidMeasurement();
        Set<String> keys = statements ? PostgresHealthMetrics.STATEMENT_METRICS.keySet() : PostgresHealthMetrics.SESSION_METRICS.keySet();
        Set<String> nullable = statements ? Set.of("maximumExecutionMs", "statsResetEpoch")
                : Set.of("oldestKnownWaitSeconds", "databaseStatsResetEpoch");
        try {
            JsonNode root = JSON.readTree(raw);
            if (root == null || !root.isObject()) throw new InvalidMeasurement();
            Set<String> actual = new HashSet<>();
            root.fieldNames().forEachRemaining(actual::add);
            if (!actual.equals(keys)) throw new InvalidMeasurement();
            Map<String, Double> result = new LinkedHashMap<>();
            for (String key : keys) {
                JsonNode value = root.get(key);
                if (value.isNull() && nullable.contains(key)) continue;
                if (!value.isNumber()) throw new InvalidMeasurement();
                BigDecimal number = value.decimalValue();
                if (number.signum() < 0 || number.compareTo(MAX_NUMBER) > 0) throw new InvalidMeasurement();
                if (!PostgresHealthMetrics.SECOND_FIELDS.contains(key) && !PostgresHealthMetrics.MILLISECOND_FIELDS.contains(key)
                        && number.stripTrailingZeros().scale() > 0) throw new InvalidMeasurement();
                double measured = number.doubleValue();
                if (!Double.isFinite(measured)) throw new InvalidMeasurement();
                result.put(key, measured);
            }
            if (statements) {
                if (result.get("groupsWithMeanOverOneSecond") > result.get("statementGroups")) throw new InvalidMeasurement();
                if ((result.get("statementGroups") == 0) != !result.containsKey("maximumExecutionMs")) throw new InvalidMeasurement();
                if (result.containsKey("statsResetEpoch") && result.get("statsResetEpoch") > nowEpoch + 1) throw new InvalidMeasurement();
            } else {
                double resetKnown = result.get("databaseStatsResetKnown");
                if ((resetKnown != 0 && resetKnown != 1)
                        || (resetKnown == 1) != result.containsKey("databaseStatsResetEpoch")) throw new InvalidMeasurement();
                if (resetKnown == 1 && result.get("databaseStatsResetEpoch") > nowEpoch + 1) throw new InvalidMeasurement();
                double postmasterStart = result.get("databasePostmasterStartEpoch");
                if (postmasterStart <= 0 || postmasterStart > nowEpoch + 1) throw new InvalidMeasurement();
                if (result.get("active") + result.get("idleInTransaction") > result.get("sessions")
                        || result.get("slowActive") > result.get("active")
                        || result.get("waitStartKnown") > result.get("waitingLocks")) throw new InvalidMeasurement();
                if (result.get("active") == 0 && result.get("oldestActiveSeconds") != 0) throw new InvalidMeasurement();
                boolean unknownAge = result.get("waitingLocks") > 0 && result.get("waitStartKnown") == 0;
                if (unknownAge == result.containsKey("oldestKnownWaitSeconds")) throw new InvalidMeasurement();
                if (result.get("waitingLocks") == 0 && result.get("oldestKnownWaitSeconds") != 0) throw new InvalidMeasurement();
            }
            return Map.copyOf(result);
        } catch (InvalidMeasurement invalid) { throw invalid; }
        catch (Exception malformed) { throw new InvalidMeasurement(); }
    }

    private static final class InvalidMeasurement extends RuntimeException { }
}
