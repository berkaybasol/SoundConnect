package com.berkayb.soundconnect.modules.admin.health;

import com.berkayb.soundconnect.modules.notification.push.PushHealthIndicator;
import com.berkayb.soundconnect.modules.notification.push.PushOperations;
import com.berkayb.soundconnect.modules.notification.push.PushProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.berkayb.soundconnect.modules.admin.health.SystemHealthSnapshot.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** Exercises the real grouped SQL, health adapter and gauges against an isolated in-memory DB. */
class PushHealthMeasurementTest {
    private static final Instant NOW = Instant.parse("2026-10-15T00:00:00Z");

    @Test void successfulEmptySummaryExportsObservedZeroWithoutInventingAnAge() {
        try (var fixture = new Fixture(true)) {
            assertThat(fixture.operations.summary().counts()).isEmpty(); // Preserve the admin API contract.
            var reading = fixture.reading();
            assertThat(reading.status()).isEqualTo(Status.UP);
            assertThat(reading.metrics()).containsExactlyInAnyOrderEntriesOf(Map.of(
                    "pending", 0d, "inFlight", 0d, "deadLetter", 0d, "suppressed", 0d, "providerAccepted", 0d));
            var meters = meters(reading);
            try {
                assertThat(gauge(meters, "observed")).isEqualTo(1);
                assertThat(gauge(meters, "pending")).isZero();
                assertThat(gauge(meters, "oldest.pending.age")).isNaN();
            } finally { meters.close(); }
        }
    }

    @Test void completedRowsKeepRealCountsAndZeroAbsentPendingGroup() {
        try (var fixture = new Fixture(true)) {
            fixture.insert("ACCEPTED", NOW.minusSeconds(3600));
            fixture.insert("ACCEPTED", NOW.minusSeconds(1800));
            assertThat(fixture.operations.summary().counts()).containsExactlyEntriesOf(Map.of("ACCEPTED", 2L));
            var reading = fixture.reading();
            assertThat(reading.metrics()).containsEntry("pending", 0d).containsEntry("providerAccepted", 2d);
            var meters = meters(reading);
            try {
                assertThat(gauge(meters, "pending")).isZero();
                assertThat(gauge(meters, "provider.accepted")).isEqualTo(2);
                assertThat(gauge(meters, "oldest.pending.age")).isNaN();
            } finally { meters.close(); }
        }
    }

    @Test void inFlightOnlyBacklogRetainsItsRealAgeDespiteZeroPending() {
        try (var fixture = new Fixture(true)) {
            fixture.insert("IN_FLIGHT", NOW.minusSeconds(3600));
            var reading = fixture.reading();
            assertThat(reading.status()).isEqualTo(Status.DEGRADED);
            assertThat(reading.metrics()).containsEntry("pending", 0d).containsEntry("inFlight", 1d)
                    .containsEntry("oldestPendingAgeSeconds", 3600d);
            var meters = meters(reading);
            try {
                assertThat(gauge(meters, "observed")).isEqualTo(1);
                assertThat(gauge(meters, "oldest.pending.age")).isEqualTo(3600);
            } finally { meters.close(); }
        }
    }

    @Test void pendingAndDeadLetterRowsRemainMeasuredAndDegraded() {
        try (var fixture = new Fixture(true)) {
            fixture.insert("PENDING", NOW.minusSeconds(3600));
            fixture.insert("DEAD_LETTER", NOW.minusSeconds(7200));
            var reading = fixture.reading();
            assertThat(reading.status()).isEqualTo(Status.DEGRADED);
            assertThat(reading.metrics()).containsEntry("pending", 1d).containsEntry("deadLetter", 1d)
                    .containsEntry("oldestPendingAgeSeconds", 3600d);
        }
    }

    @Test void failureAfterSuccessfulGroupedCountDoesNotExportHealthyZeros() {
        try (var fixture = new Fixture(false)) {
            fixture.jdbc.execute("create table tbl_push_delivery (status varchar(32))");
            fixture.jdbc.update("insert into tbl_push_delivery (status) values (?)", "PENDING");
            assertUnmeasured(fixture.reading(), Status.UNKNOWN); // The oldest-date SQL fails.
        }
    }

    @Test void missingStoreAndDisabledServiceRemainUnmeasured() {
        try (var fixture = new Fixture(false)) {
            assertUnmeasured(fixture.reading(), Status.UNKNOWN);
            when(fixture.provider.getIfAvailable()).thenReturn(null);
            assertUnmeasured(fixture.reading(), Status.DISABLED);
        }
    }

    @Test void invalidSummaryMapIsNotMistakenForASuccessfulEmptyCount() {
        try (var fixture = new Fixture(false)) {
            var invalid = mock(PushOperations.class);
            when(invalid.summary()).thenReturn(new PushOperations.Summary(null, null, false));
            when(fixture.provider.getIfAvailable()).thenReturn(invalid);
            assertUnmeasured(fixture.reading(), Status.UNKNOWN);
        }
    }

    private static void assertUnmeasured(SystemHealthProbe.Reading reading, Status expected) {
        assertThat(reading.status()).isEqualTo(expected);
        assertThat(reading.metrics()).isEmpty();
        var meters = meters(reading);
        try {
            assertThat(gauge(meters, "observed")).isZero();
            assertThat(gauge(meters, "pending")).isNaN();
            assertThat(gauge(meters, "oldest.pending.age")).isNaN();
        } finally { meters.close(); }
    }

    private static SimpleMeterRegistry meters(SystemHealthProbe.Reading reading) {
        var registry = new SimpleMeterRegistry();
        var component = new Component("push", "Push", reading.status(), reading.measuredAt(),
                reading.measuredAt() == null ? null : 0L, reading.reasonCode(), "", reading.metrics());
        new SystemHealthMeterBinder(() -> new SystemHealthSnapshot(reading.status(), NOW, 15, 60,
                List.of(component)), Set.of("push")).bindTo(registry);
        return registry;
    }

    private static double gauge(SimpleMeterRegistry registry, String suffix) {
        return registry.get("soundconnect.health." + suffix).tag("component", "push").gauge().value();
    }

    private static final class Fixture implements AutoCloseable {
        private final JdbcTemplate jdbc;
        private final PushOperations operations;
        private final ObjectProvider<PushOperations> provider;

        @SuppressWarnings("unchecked")
        private Fixture(boolean createTable) {
            var dataSource = new DriverManagerDataSource("jdbc:h2:mem:push-health-" + UUID.randomUUID()
                    + ";DB_CLOSE_DELAY=-1", "sa", "");
            jdbc = new JdbcTemplate(dataSource);
            if (createTable) jdbc.execute("create table tbl_push_delivery (status varchar(32), created_at timestamp)");
            operations = new PushOperations(new NamedParameterJdbcTemplate(dataSource), new PushProperties(),
                    Clock.fixed(NOW, ZoneOffset.UTC));
            provider = mock(ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(operations);
        }

        private void insert(String status, Instant createdAt) {
            jdbc.update("insert into tbl_push_delivery (status, created_at) values (?, ?)", status, Timestamp.from(createdAt));
        }

        private SystemHealthProbe.Reading reading() {
            return SystemHealthSources.sanitize(new PushHealthIndicator(provider).health(), NOW);
        }

        @Override public void close() { jdbc.execute("SHUTDOWN"); }
    }
}
