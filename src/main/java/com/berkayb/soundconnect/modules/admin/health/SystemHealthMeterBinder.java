package com.berkayb.soundconnect.modules.admin.health;

import com.berkayb.soundconnect.shared.telemetry.TelemetryExportMeterFilter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

import java.util.Map;
import java.util.Set;
import java.util.HashMap;
import java.util.function.Supplier;

import static com.berkayb.soundconnect.modules.admin.health.SystemHealthSnapshot.*;

/** Exports only cached, sanitized health readings. Never calls a health probe or a data service. */
public final class SystemHealthMeterBinder implements MeterBinder {
    private static final Map<String, String> EXISTING_NUMBERS = Map.ofEntries(
            Map.entry("pending", "pending"), Map.entry("inFlight", "inflight"), Map.entry("deadLetter", "deadletter"),
            Map.entry("suppressed", "suppressed"), Map.entry("retry", "retry"), Map.entry("publishing", "publishing"),
            Map.entry("review", "review"), Map.entry("stale", "stale"), Map.entry("providerAccepted", "provider.accepted"),
            Map.entry("oldestPendingAgeSeconds", "oldest.pending.age"), Map.entry("readyMessages", "ready.messages"),
            Map.entry("consumers", "consumers"), Map.entry("failed", "failed"), Map.entry("requestCount", "request.count"),
            Map.entry("serverErrorCount", "server.error.count"), Map.entry("serverErrorRatePercent", "server.error.rate"),
            Map.entry("meanLatencyMillis", "mean.latency"), Map.entry("windowSeconds", "window"),
            Map.entry("redisUsedMemoryBytes", "redis.used.memory"), Map.entry("redisMaxMemoryBytes", "redis.max.memory"),
            Map.entry("redisEvictedKeys", "redis.evicted.keys"), Map.entry("redisConnectedClients", "redis.connected.clients"),
            Map.entry("redisBlockedClients", "redis.blocked.clients"), Map.entry("redisRejectedConnections", "redis.rejected.connections"),
            Map.entry("redisTotalConnectionsReceived", "redis.total.connections.received"),
            Map.entry("redisMemoryFragmentationRatio", "redis.memory.fragmentation.ratio"), Map.entry("redisInfoLatencyMillis", "redis.info.latency"));
    private static final Map<String, String> NUMBERS = allNumbers();
    private final Supplier<SystemHealthSnapshot> snapshots;
    private final Set<String> components;
    private volatile SystemHealthSnapshot cached;
    private volatile long cachedAt;
    private boolean attempted;

    public SystemHealthMeterBinder(SystemHealthService service) { this(service::snapshot); }
    public SystemHealthMeterBinder(Supplier<SystemHealthSnapshot> snapshots) { this(snapshots, TelemetryExportMeterFilter.COMPONENTS); }
    public SystemHealthMeterBinder(Supplier<SystemHealthSnapshot> snapshots, Set<String> components) {
        if (!TelemetryExportMeterFilter.COMPONENTS.containsAll(components))
            throw new IllegalArgumentException("Unsupported health component");
        this.snapshots = snapshots;
        this.components = Set.copyOf(components);
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        for (String component : components) {
            for (Status state : Status.values())
                Gauge.builder("soundconnect.health.status", this, binder -> binder.status(component) == state ? 1d : 0d)
                        .tags("component", component, "status", state.name()).strongReference(true).register(registry);
            Gauge.builder("soundconnect.health.observed", this, binder -> observed(binder.component(component)) ? 1d : 0d)
                    .tag("component", component).strongReference(true).register(registry);
            Gauge.builder("soundconnect.health.measurement.age", this, binder -> {
                Component value = binder.component(component);
                return value == null || value.ageSeconds() == null ? Double.NaN : value.ageSeconds().doubleValue();
            }).tag("component", component).baseUnit("seconds").strongReference(true).register(registry);
            for (var number : NUMBERS.entrySet()) {
                if (!signals(component).contains(number.getKey())) continue;
                // Fixed schema, including NaN for absent signals: no dynamic metric names or labels.
                Gauge.builder("soundconnect.health." + number.getValue(), this, binder -> binder.value(component, number.getKey()))
                        .tag("component", component).baseUnit(unit(number.getKey())).strongReference(true).register(registry);
            }
        }
    }

    private double value(String name, String key) {
        Component component = component(name);
        if (!observed(component)) return Double.NaN;
        Double value = component.metrics().get(key);
        return value != null && Double.isFinite(value) && value >= 0 ? value : Double.NaN;
    }

    private Status status(String name) {
        Component component = component(name);
        return component == null ? Status.UNKNOWN : component.status();
    }

    private static boolean observed(Component component) {
        return component != null && component.measuredAt() != null
                && component.status() != Status.UNKNOWN && component.status() != Status.STALE && component.status() != Status.DISABLED;
    }

    private Component component(String id) {
        SystemHealthSnapshot snapshot = snapshot();
        return snapshot == null ? null : snapshot.components().stream().filter(value -> id.equals(value.id())).findFirst().orElse(null);
    }

    private synchronized SystemHealthSnapshot snapshot() {
        long now = System.nanoTime();
        if (!attempted || now - cachedAt >= 1_000_000_000L) {
            try { cached = snapshots.get(); }
            catch (RuntimeException unavailable) { cached = null; }
            cachedAt = now;
            attempted = true;
        }
        return cached;
    }

    private static Set<String> signals(String component) {
        return switch (component) {
            case PostgresHealthMetrics.SESSIONS -> PostgresHealthMetrics.SESSION_METRICS.keySet();
            case PostgresHealthMetrics.STATEMENTS -> PostgresHealthMetrics.STATEMENT_METRICS.keySet();
            case "redis" -> Set.of("redisUsedMemoryBytes", "redisMaxMemoryBytes", "redisEvictedKeys", "redisConnectedClients",
                    "redisBlockedClients", "redisRejectedConnections", "redisTotalConnectionsReceived", "redisMemoryFragmentationRatio", "redisInfoLatencyMillis");
            case "api" -> Set.of("requestCount", "serverErrorCount", "serverErrorRatePercent", "meanLatencyMillis", "windowSeconds");
            case "push" -> Set.of("pending", "inFlight", "deadLetter", "suppressed", "providerAccepted", "oldestPendingAgeSeconds");
            case "notificationDlq" -> Set.of("readyMessages");
            case "notificationQueue", "mailQueue", "mailDlq", "mediaQueue", "mediaDlq" -> Set.of("readyMessages", "consumers");
            case "mail", "followOutbox", "tableOutbox", "collabOutbox", "overthinkingOutbox", "eventOutbox", "studioOutbox", "venueSuggestionOutbox" ->
                    Set.of("pending", "inFlight", "deadLetter", "suppressed", "retry", "publishing", "review", "stale", "oldestPendingAgeSeconds");
            case "mediaProcessing" -> Set.of("pending", "failed", "oldestPendingAgeSeconds");
            case "mediaCleanup" -> Set.of("pending", "oldestPendingAgeSeconds");
            default -> Set.of();
        };
    }

    private static String unit(String key) {
        if (PostgresHealthMetrics.SECOND_FIELDS.contains(key)) return "seconds";
        if (PostgresHealthMetrics.MILLISECOND_FIELDS.contains(key)) return "milliseconds";
        return switch (key) {
            case "oldestPendingAgeSeconds", "windowSeconds" -> "seconds";
            case "meanLatencyMillis", "redisInfoLatencyMillis" -> "milliseconds";
            case "redisUsedMemoryBytes", "redisMaxMemoryBytes" -> "bytes";
            case "serverErrorRatePercent" -> "percent";
            default -> null;
        };
    }

    private static Map<String, String> allNumbers() {
        Map<String, String> result = new HashMap<>(EXISTING_NUMBERS);
        result.putAll(PostgresHealthMetrics.SESSION_METRICS);
        result.putAll(PostgresHealthMetrics.STATEMENT_METRICS);
        return Map.copyOf(result);
    }
}
