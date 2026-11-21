package com.berkayb.soundconnect.modules.admin.health;

import java.util.Map;
import java.util.Set;

/** Fixed numeric schema shared by the SQL reader and cached health meter binder. */
public final class PostgresHealthMetrics {
    public static final String SESSIONS = "databaseSessions";
    public static final String STATEMENTS = "databaseStatements";
    // The original eight fields are own-role observations. Explicit database.*
    // fields below are current-database aggregates, shared by both applications.
    public static final Map<String, String> SESSION_METRICS = Map.ofEntries(
            Map.entry("sessions", "postgres.sessions"),
            Map.entry("active", "postgres.active.sessions"),
            Map.entry("idleInTransaction", "postgres.idle.in.transaction.sessions"),
            Map.entry("slowActive", "postgres.slow.active.sessions"),
            Map.entry("oldestActiveSeconds", "postgres.oldest.active.age"),
            Map.entry("waitingLocks", "postgres.waiting.locks"),
            Map.entry("waitStartKnown", "postgres.wait.start.known"),
            Map.entry("oldestKnownWaitSeconds", "postgres.oldest.known.wait.age"),
            Map.entry("databaseDeadlocks", "postgres.database.deadlocks"),
            Map.entry("databaseStatsResetKnown", "postgres.database.stats.reset.known"),
            Map.entry("databaseStatsResetEpoch", "postgres.database.stats.reset.epoch"),
            Map.entry("databasePostmasterStartEpoch", "postgres.database.postmaster.start.epoch"));
    public static final Map<String, String> STATEMENT_METRICS = Map.of(
            "statementGroups", "postgres.statement.groups",
            "calls", "postgres.statement.calls",
            "totalExecutionMs", "postgres.statement.execution.total",
            "maximumExecutionMs", "postgres.statement.execution.maximum",
            "groupsWithMeanOverOneSecond", "postgres.statement.slow.mean.groups",
            "blocksRead", "postgres.statement.blocks.read",
            "blocksHit", "postgres.statement.blocks.hit",
            "deallocatedGroups", "postgres.statement.groups.deallocated",
            "statsResetEpoch", "postgres.statement.stats.reset");
    public static final Set<String> SECOND_FIELDS = Set.of("oldestActiveSeconds", "oldestKnownWaitSeconds", "statsResetEpoch", "databaseStatsResetEpoch", "databasePostmasterStartEpoch");
    public static final Set<String> MILLISECOND_FIELDS = Set.of("totalExecutionMs", "maximumExecutionMs");

    private PostgresHealthMetrics() { }
}
