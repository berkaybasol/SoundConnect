package com.berkayb.soundconnect.modules.admin.health;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.berkayb.soundconnect.modules.admin.health.SystemHealthSnapshot.*;
import static org.assertj.core.api.Assertions.*;

class PostgresHealthMeterBinderTest {
    @Test void workerBindsTwelveNumbersAndEightStatusFreshnessSeries() {
        var registry = new SimpleMeterRegistry();
        try {
            var values = PostgresHealthObservation.parse(PostgresHealthObservationTest.EMPTY_SESSIONS, false, 0);
            new SystemHealthMeterBinder(() -> snapshot(component(PostgresHealthMetrics.SESSIONS, Status.UP, values)),
                    Set.of(PostgresHealthMetrics.SESSIONS)).bindTo(registry);
            assertThat(registry.getMeters()).hasSize(20);
            assertThat(registry.getMeters()).allSatisfy(meter -> {
                assertThat(meter.getId().getTag("component")).isEqualTo(PostgresHealthMetrics.SESSIONS);
                assertThat(meter.getId().getTags()).allSatisfy(tag -> assertThat(tag.getKey()).isIn("component", "status"));
            });
            assertThat(registry.get("soundconnect.health.postgres.sessions").gauge().value()).isZero();
            assertThat(registry.get("soundconnect.health.postgres.database.deadlocks").gauge().value()).isZero();
            assertThat(registry.get("soundconnect.health.postgres.database.stats.reset.known").gauge().value()).isZero();
            assertThat(registry.get("soundconnect.health.postgres.database.stats.reset.epoch").gauge().value()).isNaN();
            assertThat(registry.get("soundconnect.health.observed").gauge().value()).isEqualTo(1);
        } finally { registry.close(); }
    }

    @Test void failedSessionProbeDoesNotMaskSuccessfulWrapperAndNullableFieldsStayNan() {
        var registry = new SimpleMeterRegistry();
        try {
            var statements = PostgresHealthObservation.parse(PostgresHealthObservationTest.EMPTY_STATEMENTS, true, 0);
            var sessions = PostgresHealthObservation.parse(PostgresHealthObservationTest.EMPTY_SESSIONS, false, 0);
            new SystemHealthMeterBinder(() -> snapshot(
                    component(PostgresHealthMetrics.SESSIONS, Status.UNKNOWN, sessions),
                    component(PostgresHealthMetrics.STATEMENTS, Status.UP, statements)),
                    Set.of(PostgresHealthMetrics.SESSIONS, PostgresHealthMetrics.STATEMENTS)).bindTo(registry);
            assertThat(registry.getMeters()).hasSize(37);
            assertThat(registry.get("soundconnect.health.postgres.sessions").gauge().value()).isNaN();
            assertThat(registry.get("soundconnect.health.postgres.database.deadlocks").gauge().value()).isNaN();
            assertThat(registry.get("soundconnect.health.postgres.database.stats.reset.known").gauge().value()).isNaN();
            assertThat(registry.get("soundconnect.health.observed").tag("component", PostgresHealthMetrics.SESSIONS).gauge().value()).isZero();
            assertThat(registry.get("soundconnect.health.observed").tag("component", PostgresHealthMetrics.STATEMENTS).gauge().value()).isEqualTo(1);
            assertThat(registry.get("soundconnect.health.postgres.statement.calls").gauge().value()).isZero();
            assertThat(registry.get("soundconnect.health.postgres.statement.execution.maximum").gauge().value()).isNaN();
            assertThat(registry.get("soundconnect.health.postgres.statement.stats.reset").gauge().value()).isNaN();
            assertThat(registry.get("soundconnect.health.postgres.statement.execution.total").gauge().getId().getBaseUnit()).isEqualTo("milliseconds");
            assertThat(registry.get("soundconnect.health.postgres.oldest.active.age").gauge().getId().getBaseUnit()).isEqualTo("seconds");
        } finally { registry.close(); }
    }

    @Test void staleAndDisabledValuesNeverBecomeHealthyZeros() {
        for (Status status : List.of(Status.UNKNOWN, Status.STALE, Status.DISABLED)) {
            var registry = new SimpleMeterRegistry();
            try {
                var values = PostgresHealthObservation.parse(PostgresHealthObservationTest.EMPTY_SESSIONS, false, 0);
                new SystemHealthMeterBinder(() -> snapshot(component(PostgresHealthMetrics.SESSIONS, status, values)),
                        Set.of(PostgresHealthMetrics.SESSIONS)).bindTo(registry);
                assertThat(registry.get("soundconnect.health.postgres.sessions").gauge().value()).isNaN();
                assertThat(registry.get("soundconnect.health.postgres.database.deadlocks").gauge().value()).isNaN();
                assertThat(registry.get("soundconnect.health.postgres.database.stats.reset.known").gauge().value()).isNaN();
                assertThat(registry.get("soundconnect.health.observed").gauge().value()).isZero();
            } finally { registry.close(); }
        }
    }

    @Test void measuredDatabaseResetEpochRetainsItsUnitWithoutDatabaseIdentityLabels() {
        String raw = PostgresHealthObservationTest.EMPTY_SESSIONS
                .replace("\"databaseDeadlocks\":0", "\"databaseDeadlocks\":17")
                .replace("\"databaseStatsResetKnown\":0", "\"databaseStatsResetKnown\":1")
                .replace("\"databaseStatsResetEpoch\":null", "\"databaseStatsResetEpoch\":1.125");
        var registry = new SimpleMeterRegistry();
        try {
            new SystemHealthMeterBinder(() -> snapshot(component(PostgresHealthMetrics.SESSIONS, Status.UP,
                    PostgresHealthObservation.parse(raw, false, 2))), Set.of(PostgresHealthMetrics.SESSIONS)).bindTo(registry);
            assertThat(registry.get("soundconnect.health.postgres.database.deadlocks").gauge().value()).isEqualTo(17);
            var epoch = registry.get("soundconnect.health.postgres.database.stats.reset.epoch").gauge();
            assertThat(epoch.value()).isEqualTo(1.125);
            assertThat(epoch.getId().getBaseUnit()).isEqualTo("seconds");
            assertThat(epoch.getId().getTags()).extracting(tag -> tag.getKey()).containsExactly("component");
        } finally { registry.close(); }
    }

    @Test void postmasterIdentityUsesSecondsAndNullableResetNeverBecomesZero() {
        var registry = new SimpleMeterRegistry();
        try {
            var values = PostgresHealthObservation.parse(PostgresHealthObservationTest.EMPTY_SESSIONS, false, 2);
            new SystemHealthMeterBinder(() -> snapshot(component(PostgresHealthMetrics.SESSIONS, Status.UP, values)),
                    Set.of(PostgresHealthMetrics.SESSIONS)).bindTo(registry);
            var started = registry.get("soundconnect.health.postgres.database.postmaster.start.epoch").gauge();
            assertThat(started.value()).isEqualTo(0.5);
            assertThat(started.getId().getBaseUnit()).isEqualTo("seconds");
            assertThat(started.getId().getTags()).extracting(tag -> tag.getKey()).containsExactly("component");
            assertThat(registry.get("soundconnect.health.postgres.database.stats.reset.epoch").gauge().value()).isNaN();
            assertThat(registry.get("soundconnect.health.postgres.database.stats.reset.known").gauge().value()).isZero();
        } finally { registry.close(); }
    }

    @Test void arbitraryComponentCannotCreateDynamicLabels() {
        assertThatThrownBy(() -> new SystemHealthMeterBinder(() -> null, Set.of("private-role-name")))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Unsupported health component");
    }

    private static Component component(String id, Status status, Map<String, Double> values) {
        return new Component(id, "DB", status, Instant.now(), 0L, ReasonCode.HEALTHY, "numeric only", values);
    }
    private static SystemHealthSnapshot snapshot(Component... components) {
        return new SystemHealthSnapshot(Status.UNKNOWN, Instant.now(), 15, 60, List.of(components));
    }
}
