package com.berkayb.soundconnect.shared.telemetry;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class TelemetryExportMeterFilterTest {
    @Test void postgresGaugesExportOnlyUnderTheirOwnFiniteComponentAndCannotBecomeCounters() {
        var filter = new TelemetryExportMeterFilter(1024);
        var schemas = java.util.Map.of(
                "databaseSessions", com.berkayb.soundconnect.modules.admin.health.PostgresHealthMetrics.SESSION_METRICS,
                "databaseStatements", com.berkayb.soundconnect.modules.admin.health.PostgresHealthMetrics.STATEMENT_METRICS);
        for (var schema : schemas.entrySet()) {
            for (String suffix : schema.getValue().values()) {
                String name = "soundconnect.health." + suffix;
                var safe = new Meter.Id(name, io.micrometer.core.instrument.Tags.of("component", schema.getKey()), null, null, Meter.Type.GAUGE);
                assertThat(filter.canExport(safe)).isTrue();
                assertThat(filter.canExport(new Meter.Id(name, io.micrometer.core.instrument.Tags.of(safe.getTags()), null, null, Meter.Type.COUNTER))).isFalse();
                for (String wrong : new String[]{"database", "mediaQueue", schema.getKey().equals("databaseSessions") ? "databaseStatements" : "databaseSessions"})
                    assertThat(filter.canExport(new Meter.Id(name, io.micrometer.core.instrument.Tags.of("component", wrong), null, null, Meter.Type.GAUGE))).isFalse();
                var privateTagged = new Meter.Id(name, io.micrometer.core.instrument.Tags.of(safe.getTags()).and("query", "SELECT private_payload"), null, null, Meter.Type.GAUGE);
                assertThat(filter.canExport(privateTagged)).isFalse();
                assertThat(filter.map(privateTagged)).isEqualTo(safe);
            }
        }
    }

    @Test void rawUrlsAccountTagsExceptionsDescriptionsAndPoolNamesCannotLeaveExportRegistry() {
        var export = new SimpleMeterRegistry();
        var local = new SimpleMeterRegistry();
        var composite = new CompositeMeterRegistry();
        try {
            export.config().meterFilter(new TelemetryExportMeterFilter(1024));
            composite.add(local).add(export);
            Timer.builder("http.server.requests").description("SECRET description")
                    .tags("uri", "/api/v1/profiles/SECRET", "method", "GET", "status", "200",
                            "userId", "SECRET", "exception", "SECRET.Exception", "authorization", "Bearer SECRET")
                    .register(composite).record(100, TimeUnit.MILLISECONDS);
            composite.gauge("hikaricp.connections.active", java.util.List.of(Tag.of("pool", "SECRET-pool")), 2d);
            composite.counter("SECRET.business.metric", "account", "SECRET").increment();
            assertThat(local.find("SECRET.business.metric").counter().count()).isEqualTo(1);
            assertThat(local.find("http.server.requests").tag("uri", "/api/v1/profiles/SECRET").timer()).isNotNull();
            assertThat(export.find("SECRET.business.metric").counter()).isNull();
            assertThat(export.getMeters().stream().map(Meter::getId).toList().toString()).doesNotContain("SECRET", "authorization", "userId", "exception");
            assertThat(export.find("http.server.requests").tag("uri", "profiles").tag("status", "2xx").timer().count()).isEqualTo(1);
            assertThat(export.find("hikaricp.connections.active").tag("pool", "primary").gauge()).isNotNull();
        } finally {
            composite.close();
            local.close();
            export.close();
        }
    }

    @Test void tenThousandRawRoutesAndArbitraryTagsCollapseToOneSafeSeriesWithoutTouchingLocalSemantics() {
        var registry = new SimpleMeterRegistry();
        try {
            var filter = new TelemetryExportMeterFilter(1024);
            registry.config().meterFilter(filter);
            for (int index = 0; index < 10000; index++) {
                registry.timer("http.server.requests", "uri", "/api/v1/users/secret-" + index,
                        "method", "GET", "status", "200", "raw-query", "email-" + index).record(1, TimeUnit.MILLISECONDS);
            }
            assertThat(registry.getMeters()).hasSize(1);
            assertThat(registry.find("http.server.requests").timer().count()).isEqualTo(10000);
            assertThat(filter.usedWireSeries()).isEqualTo(TelemetryExportMeterFilter.HISTOGRAM_WIRE_SERIES);
            assertThat(registry.getMeters().getFirst().getId().getTags()).containsExactly(
                    Tag.of("method", "GET"), Tag.of("status", "2xx"), Tag.of("uri", "profiles"));
        } finally {
            registry.close();
        }
    }

    @Test void weightedWireBudgetCountsBucketsAndStopsBeforeLimit() {
        var registry = new SimpleMeterRegistry();
        try {
            var filter = new TelemetryExportMeterFilter(30);
            registry.config().meterFilter(filter);
            for (String method : new String[]{"GET", "POST", "PUT", "DELETE", "PATCH", "HEAD"})
                registry.timer("http.server.requests", "uri", "/api/v1/media", "method", method, "status", "200").record(1, TimeUnit.MILLISECONDS);
            assertThat(registry.find("http.server.requests").timers()).hasSize(2);
            assertThat(filter.usedWireSeries()).isEqualTo(26).isLessThanOrEqualTo(30);
            assertThat(filter.deniedMeters()).isGreaterThan(0);
            assertThat(registry.find("http.server.requests").timers()).allSatisfy(timer ->
                    assertThat(timer.takeSnapshot().histogramCounts()).hasSize(10));
        } finally {
            registry.close();
        }
    }

    @Test void laterCommonTagFilterCannotReintroduceSecrets() {
        var registry = new SimpleMeterRegistry();
        try {
            registry.config().meterFilter(new TelemetryExportMeterFilter(1024));
            registry.config().meterFilter(MeterFilter.commonTags(java.util.List.of(Tag.of("SECRET", "private-account"))));
            registry.counter("jvm.classes.unloaded").increment();
            assertThat(registry.getMeters()).isEmpty();
        } finally {
            registry.close();
        }
    }

    @Test void queryEncodedAndUnknownPathsNeverBecomeLabels() {
        for (String raw : new String[]{"/api/v1/auth?token=SECRET", "/api/v1/SECRET", "/api/v1/%75sers/SECRET",
                "https://SECRET@example.com", "/api/v1/auth\nSECRET", "NOT_FOUND", "UNKNOWN"})
            assertThat(TelemetryExportMeterFilter.routeGroup(raw)).isEqualTo("other");
        assertThat(TelemetryExportMeterFilter.routeGroup("/actuator/health/readiness")).isEqualTo("monitoring");
    }
}
