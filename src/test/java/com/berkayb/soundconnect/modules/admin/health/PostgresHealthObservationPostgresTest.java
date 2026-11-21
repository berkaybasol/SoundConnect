package com.berkayb.soundconnect.modules.admin.health;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/**
 * Real SQL and role boundaries in one owned network-none PostgreSQL. No published
 * port, shared database, staging credentials or application tables. JDBC cleanup
 * is separately exercised by PostgresHealthObservationTest with controlled fakes.
 */
@Testcontainers(disabledWithoutDocker = true)
@Timeout(45)
class PostgresHealthObservationPostgresTest {
    @Container
    static final GenericContainer<?> POSTGRES = new GenericContainer<>("postgres:16.4-alpine")
            .withEnv("POSTGRES_PASSWORD", "owned-observation-fixture-only")
            .withEnv("POSTGRES_HOST_AUTH_METHOD", "trust")
            .withNetworkMode("none")
            .withTmpFs(java.util.Map.of("/var/lib/postgresql/data", "rw,noexec,nosuid,size=96m",
                    "/var/run/postgresql", "rw,noexec,nosuid,size=1m", "/tmp", "rw,noexec,nosuid,size=8m"))
            .withCreateContainerCmdModifier(command -> command.getHostConfig().withMemory(256L * 1024 * 1024)
                    .withMemorySwap(256L * 1024 * 1024).withPidsLimit(128L).withNanoCPUs(1_000_000_000L).withReadonlyRootfs(true))
            .withCommand("postgres", "-c", "shared_preload_libraries=pg_stat_statements", "-c", "shared_buffers=16MB", "-c", "max_connections=20")
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2).withStartupTimeout(Duration.ofSeconds(60)));
    private final List<CompletableFuture<?>> background = new ArrayList<>();

    @BeforeAll static void createOnlyOwnedFixtureRolesAndPrivateWrapper() throws Exception {
        sql("postgres", """
                CREATE ROLE observe_api LOGIN;
                CREATE ROLE observe_worker LOGIN;
                CREATE SCHEMA observe_private;
                REVOKE ALL ON SCHEMA observe_private FROM PUBLIC;
                CREATE EXTENSION pg_stat_statements WITH SCHEMA observe_private;
                REVOKE ALL ON ALL FUNCTIONS IN SCHEMA observe_private FROM PUBLIC;
                REVOKE ALL ON ALL TABLES IN SCHEMA observe_private FROM PUBLIC;
                CREATE FUNCTION public.soundconnect_db_statement_snapshot_v1() RETURNS jsonb
                LANGUAGE SQL VOLATILE PARALLEL UNSAFE SECURITY DEFINER SET search_path=pg_catalog,pg_temp
                BEGIN ATOMIC
                  WITH s AS (
                    SELECT count(*) groups,coalesce(sum(calls),0) calls,coalesce(sum(total_exec_time),0) total_ms,
                           max(max_exec_time) max_ms,count(*) FILTER(WHERE mean_exec_time>=1000) slow_groups,
                           coalesce(sum(shared_blks_read),0) blocks_read,coalesce(sum(shared_blks_hit),0) blocks_hit
                    FROM observe_private.pg_stat_statements(false)
                    WHERE dbid=(SELECT oid FROM pg_database WHERE datname=current_database())
                  ), i AS (SELECT dealloc,stats_reset FROM observe_private.pg_stat_statements_info())
                  SELECT jsonb_build_object('statementGroups',s.groups,'calls',s.calls,'totalExecutionMs',s.total_ms,
                           'maximumExecutionMs',s.max_ms,'groupsWithMeanOverOneSecond',s.slow_groups,
                           'blocksRead',s.blocks_read,'blocksHit',s.blocks_hit,'deallocatedGroups',i.dealloc,
                           'statsResetEpoch',extract(epoch FROM i.stats_reset)) FROM s CROSS JOIN i;
                END;
                REVOKE ALL ON FUNCTION public.soundconnect_db_statement_snapshot_v1() FROM PUBLIC;
                GRANT EXECUTE ON FUNCTION public.soundconnect_db_statement_snapshot_v1() TO observe_api;
                """);
    }

    @AfterEach void releaseOnlyOwnedSessions() throws Exception {
        sql("postgres", "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE usename IN ('observe_api','observe_worker'); ALTER ROLE observe_api RESET track_activities");
        for (var task : background) task.get(5, TimeUnit.SECONDS);
    }

    @Test void sameFixedQueryIsolatesApiAndWorkerWithoutBroadReadPrivileges() throws Exception {
        busy("observe_worker", "SELECT pg_sleep(20)");
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            var worker = sample("observe_worker");
            assertThat(worker.get("active")).isEqualTo(1);
            assertThat(worker.get("slowActive")).isEqualTo(1);
            assertThat(worker.get("oldestActiveSeconds")).isGreaterThanOrEqualTo(1);
        });
        var api = sample("observe_api");
        assertThat(api).hasSize(api.get("databaseStatsResetKnown") == 1d ? 12 : 11)
                .containsEntry("sessions", 0d).containsEntry("active", 0d).containsEntry("databaseDeadlocks", 0d);
        assertThat(sql("observe_api", "SELECT pg_has_role(current_user,'pg_read_all_stats','USAGE'),pg_has_role(current_user,'pg_monitor','USAGE')").trim()).isEqualTo("f|f");
    }

    @Test void ownBlockedLockReportsKnownWaitAgeAndNeverIncludesOtherRoleLocks() throws Exception {
        busy("observe_api", "BEGIN; SELECT pg_advisory_xact_lock(71234234); SELECT pg_sleep(20)");
        await().atMost(Duration.ofSeconds(4)).untilAsserted(() -> assertThat(sample("observe_api").get("active")).isEqualTo(1));
        busy("observe_api", "BEGIN; SELECT pg_advisory_xact_lock(71234234)");
        await().atMost(Duration.ofSeconds(4)).untilAsserted(() -> {
            var api = sample("observe_api");
            assertThat(api.get("waitingLocks")).isEqualTo(1);
            assertThat(api.get("waitStartKnown")).isEqualTo(1);
            assertThat(api.get("oldestKnownWaitSeconds")).isNotNull().isNotNegative();
        });
        assertThat(sample("observe_worker")).containsEntry("waitingLocks", 0d).containsEntry("oldestKnownWaitSeconds", 0d);
    }

    @Test void disabledActivityTrackingIsUnknownEvenWhenNoOtherOwnSessionsExist() throws Exception {
        sql("postgres", "ALTER ROLE observe_api SET track_activities=off");
        String raw = sql("observe_api", PostgresHealthObservation.SESSIONS_SQL).trim();
        assertThat(raw).isEmpty();
        assertThatThrownBy(() -> PostgresHealthObservation.parse(raw, false, Instant.now().getEpochSecond())).isInstanceOf(RuntimeException.class);
    }

    @Test void apiWrapperYieldsOnlyNineAggregateFieldsWhileNativeAndWorkerAccessRemainDenied() throws Exception {
        String raw = sql("observe_api", PostgresHealthObservation.STATEMENTS_SQL).trim();
        var metrics = PostgresHealthObservation.parse(raw, true, Instant.now().getEpochSecond());
        assertThat(metrics).hasSize(9);
        assertThat(metrics.get("statementGroups")).isPositive();
        assertThat(metrics.get("calls")).isPositive();
        assertThat(metrics.values()).allSatisfy(value -> assertThat(value).isFinite().isNotNegative());
        assertThat(raw).doesNotContain("observe_api", "observe_worker", "query", "queryid", "userid");
        assertThat(execute("observe_worker", PostgresHealthObservation.STATEMENTS_SQL).getExitCode()).isNotZero();
        assertThat(execute("observe_api", "SELECT * FROM observe_private.pg_stat_statements(false)").getExitCode()).isNotZero();
    }

    @Test void realReadOnlyAndServerStatementTimeoutRejectWritesAndBoundSleep() throws Exception {
        var write = execute("postgres", "BEGIN READ ONLY; SET LOCAL statement_timeout='100ms'; CREATE TABLE forbidden_write(a int)");
        assertThat(write.getExitCode()).isNotZero();
        assertThat(write.getStderr()).contains("read-only");
        assertThat(sql("postgres", "SELECT to_regclass('public.forbidden_write') IS NULL").trim()).isEqualTo("t");
        long before = System.nanoTime();
        var timedOut = execute("observe_api", "BEGIN READ ONLY; SET LOCAL statement_timeout='100ms'; SELECT pg_sleep(10)");
        assertThat(timedOut.getExitCode()).isNotZero();
        assertThat(Duration.ofNanos(System.nanoTime() - before)).isLessThan(Duration.ofSeconds(3));
        assertThat(timedOut.getStderr()).contains("statement timeout");
    }

    private void busy(String role, String query) {
        background.add(CompletableFuture.supplyAsync(() -> {
            try { return execute(role, query); }
            catch (Exception failure) { throw new IllegalStateException("Owned fixture command failed"); }
        }));
    }
    private static java.util.Map<String, Double> sample(String role) throws Exception {
        return PostgresHealthObservation.parse(sql(role, PostgresHealthObservation.SESSIONS_SQL).trim(), false, Instant.now().getEpochSecond());
    }
    private static String sql(String role, String query) throws Exception {
        var result = execute(role, query);
        assertThat(result.getExitCode()).as("owned PostgreSQL fixture command").isZero();
        return result.getStdout();
    }
    private static org.testcontainers.containers.Container.ExecResult execute(String role, String query) throws Exception {
        return POSTGRES.execInContainer("psql", "-X", "-q", "-A", "-t", "-v", "ON_ERROR_STOP=1", "-U", role, "-d", "postgres", "-c", query);
    }
}
