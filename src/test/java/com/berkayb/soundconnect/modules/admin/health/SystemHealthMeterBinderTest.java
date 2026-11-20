package com.berkayb.soundconnect.modules.admin.health;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static com.berkayb.soundconnect.modules.admin.health.SystemHealthSnapshot.*;
import static org.assertj.core.api.Assertions.assertThat;

class SystemHealthMeterBinderTest {
    @ParameterizedTest
    @EnumSource(value = Status.class, names = {"UNKNOWN", "STALE", "DISABLED"})
    void staleOrUnknownPreviousNumbersAreNeverReportedAsCurrentHealthyZeros(Status status) {
        var registry = new SimpleMeterRegistry();
        try {
            new SystemHealthMeterBinder(() -> snapshot(status, Map.of("pending", 17d))).bindTo(registry);
            assertThat(registry.get("soundconnect.health.status").tags("component", "push", "status", status.name()).gauge().value()).isEqualTo(1);
            assertThat(registry.get("soundconnect.health.status").tags("component", "push", "status", "UP").gauge().value()).isZero();
            assertThat(registry.get("soundconnect.health.observed").tag("component", "push").gauge().value()).isZero();
            assertThat(registry.get("soundconnect.health.pending").tag("component", "push").gauge().value()).isNaN();
            assertThat(registry.get("soundconnect.health.measurement.age").tag("component", "push").gauge().value()).isEqualTo(120);
        } finally {
            registry.close();
        }
    }

    @Test void realZeroIsDistinguishableFromAbsentMeasurementAndDynamicDetailsAreExcluded() {
        var registry = new SimpleMeterRegistry();
        try {
            new SystemHealthMeterBinder(() -> snapshot(Status.UP, Map.of("pending", 0d, "SECRET-email", 1d))).bindTo(registry);
            assertThat(registry.get("soundconnect.health.pending").tag("component", "push").gauge().value()).isZero();
            assertThat(registry.get("soundconnect.health.observed").tag("component", "push").gauge().value()).isEqualTo(1);
            assertThat(registry.get("soundconnect.health.inflight").tag("component", "push").gauge().value()).isNaN();
            assertThat(registry.get("soundconnect.health.status").tags("component", "database", "status", "UNKNOWN").gauge().value()).isEqualTo(1);
            assertThat(registry.get("soundconnect.health.measurement.age").tag("component", "database").gauge().value()).isNaN();
            assertThat(registry.getMeters().stream().map(Meter::getId).toList().toString()).doesNotContain("SECRET", "private-label", "private-impact");
        } finally {
            registry.close();
        }
    }

    @Test void oneCachedSnapshotSuppliesAllGaugesAndSchemaHasFiniteBudget() {
        var reads = new AtomicInteger();
        var registry = new SimpleMeterRegistry();
        try {
            new SystemHealthMeterBinder(() -> { reads.incrementAndGet(); return snapshot(Status.UP, Map.of()); }).bindTo(registry);
            registry.getMeters().stream().map(Gauge.class::cast).forEach(Gauge::value);
            assertThat(reads).hasValue(1);
            assertThat(registry.getMeters()).hasSize(341); // Existing 308 + two fixed PostgreSQL components (33).
        } finally {
            registry.close();
        }
    }

    @Test void mailAndVenueExistingAgeGaugesKeepAbsentInvalidAndStaleReadingsUnknown() {
        var now=Instant.parse("2026-10-12T00:00:00Z");
        for (String id:List.of("mail","venueSuggestionOutbox")) {
            for (Status status:List.of(Status.UP,Status.DEGRADED,Status.UNKNOWN,Status.STALE)) {
                for (Map<String,Double> values:List.of(Map.<String,Double>of(),Map.of("oldestPendingAgeSeconds",3600d),
                        Map.of("oldestPendingAgeSeconds",0d),Map.of("oldestPendingAgeSeconds",Double.NaN),
                        Map.of("oldestPendingAgeSeconds",Double.POSITIVE_INFINITY),Map.of("oldestPendingAgeSeconds",-1d))) {
                    var registry=new SimpleMeterRegistry();
                    try {
                        var snapshot=new SystemHealthSnapshot(status,now,15,60,List.of(new Component(id,id,status,
                                now,0L,ReasonCode.HEALTHY,"",values)));
                        new SystemHealthMeterBinder(()->snapshot,java.util.Set.of(id)).bindTo(registry);
                        var age=registry.get("soundconnect.health.oldest.pending.age").tag("component",id).gauge();
                        var value=values.get("oldestPendingAgeSeconds");
                        if ((status==Status.UP || status==Status.DEGRADED) && value!=null && Double.isFinite(value) && value>=0)
                            assertThat(age.value()).as(id+"/"+status).isEqualTo(value);
                        else assertThat(age.value()).as(id+"/"+status).isNaN();
                        assertThat(age.getId().getBaseUnit()).isEqualTo("seconds");
                    } finally {
                        registry.close();
                    }
                }
            }
        }
    }

    private static SystemHealthSnapshot snapshot(Status status, Map<String, Double> numbers) {
        Instant now = Instant.parse("2026-10-11T00:00:00Z");
        long age = status == Status.UP ? 0 : 120;
        return new SystemHealthSnapshot(status, now, 15, 60, List.of(new Component("push", "private-label", status,
                now.minusSeconds(age), age, ReasonCode.MEASUREMENT_STALE, "private-impact", numbers)));
    }
}
