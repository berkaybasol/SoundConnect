package com.berkayb.soundconnect.shared.telemetry;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.config.MeterFilterReply;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Applied only to the outbound registry; product-local metrics keep their original contract. */
public final class TelemetryExportMeterFilter implements MeterFilter {
    // Ten finite boundaries + +Inf bucket + count + sum after Prometheus conversion.
    public static final int HISTOGRAM_WIRE_SERIES = 13;
    public static final Set<String> EXPORT_METRICS = Set.of("soundconnect.telemetry.export.series.budget",
            "soundconnect.telemetry.export.series.used", "soundconnect.telemetry.export.meters.denied",
            "soundconnect.telemetry.export.failures", "soundconnect.telemetry.export.unsupported", "soundconnect.telemetry.export.last.success");
    public static final Set<String> WORKER_METRICS = Set.of("soundconnect.worker.job.duration", "soundconnect.worker.job.active",
            "soundconnect.worker.admission", "soundconnect.worker.temp.reserved", "soundconnect.worker.temp.limit",
            "soundconnect.worker.temp.wait", "soundconnect.worker.lease", "soundconnect.worker.recovery",
            "soundconnect.worker.ffmpeg.cpu.time", "soundconnect.worker.ffmpeg.active");
    public static final Set<String> WORKFLOWS = Set.of("hls", "image_thumbnail");
    public static final Set<String> WORKER_OUTCOMES = Set.of("success", "failure", "lease_lost", "fenced", "interrupted",
            "accepted", "saturated", "rejected", "acquired", "timeout", "renewed", "lost", "retry_cleanup", "terminal_cleanup", "stale_sent");
    public static final Set<String> BACKGROUND_METRICS = Set.of("soundconnect.scheduler.duration", "soundconnect.scheduler.active",
            "soundconnect.scheduler.last.started", "soundconnect.scheduler.last.completed", "soundconnect.notification.duplicate.attempts");
    public static final Set<String> SCHEDULES = Set.of("follow_outbox", "table_outbox", "collab_outbox", "overthinking_outbox", "event_outbox", "studio_outbox", "campaigns");
    public static final Set<String> SCHEDULER_OUTCOMES = Set.of("completed", "failed");
    public static final Set<String> DEPENDENCY_METRICS = Set.of("soundconnect.dependency.duration");
    public static final Set<String> DEPENDENCY_PROVIDERS = Set.of("mailersend", "fcm", "s3", "cloudfront");
    public static final Set<String> DEPENDENCY_OPERATIONS = Set.of("send", "head_bucket", "head_object", "copy_object", "delete_object",
            "list_objects", "delete_objects", "put_object", "open_object", "download_object", "invalidate");
    public static final Set<String> DEPENDENCY_RESULTS = Set.of("accepted", "success", "error");
    public static final Set<String> AUTH_METRICS = Set.of("soundconnect.auth.ratelimit.decisions", "soundconnect.auth.ratelimit.duration");
    public static final Set<String> WEBSOCKET_METRICS = Set.of("soundconnect.websocket.observed", "soundconnect.websocket.sessions.active",
            "soundconnect.websocket.sessions.opened", "soundconnect.websocket.sessions.closed", "soundconnect.websocket.transport.errors");
    public static final Set<String> AUTH_DIMENSIONS = Set.of("ip", "account");
    public static final Set<String> AUTH_DECISIONS = Set.of("permitted", "limited", "unavailable", "disabled");
    private static final Set<String> ROUTE_GROUPS = Set.of("auth", "admin", "media", "profiles", "feed", "notifications",
            "messaging", "venues", "studios", "events", "table-groups", "collabs", "marketplace", "analytics", "catalog", "other", "monitoring");
    public static final Set<String> RUNTIME_METRICS = Set.of(
            "jvm.memory.used", "jvm.memory.committed", "jvm.memory.max",
            "jvm.buffer.count", "jvm.buffer.memory.used", "jvm.buffer.total.capacity",
            "jvm.threads.live", "jvm.threads.daemon", "jvm.threads.peak", "jvm.threads.states", "jvm.threads.started",
            "jvm.classes.loaded", "jvm.classes.unloaded", "jvm.gc.pause", "jvm.gc.concurrent.phase.time",
            "jvm.gc.memory.allocated", "jvm.gc.memory.promoted", "jvm.gc.live.data.size", "jvm.gc.max.data.size",
            "jvm.gc.overhead", "process.cpu.usage", "process.uptime", "process.start.time",
            "process.files.open", "process.files.max", "system.cpu.usage", "system.cpu.count", "system.load.average.1m",
            "hikaricp.connections", "hikaricp.connections.active", "hikaricp.connections.idle",
            "hikaricp.connections.pending", "hikaricp.connections.max", "hikaricp.connections.min",
            "hikaricp.connections.acquire", "hikaricp.connections.usage", "hikaricp.connections.creation",
            "hikaricp.connections.timeout", "http.server.requests");
    public static final Set<String> COMPONENTS = Set.of("api", "database", "databaseSessions", "databaseStatements", "redis", "rabbit", "realtime", "push", "mail",
            "notificationDlq", "notificationQueue", "mailQueue", "mailDlq", "mediaQueue", "mediaDlq", "followOutbox",
            "tableOutbox", "collabOutbox", "overthinkingOutbox", "eventOutbox", "studioOutbox", "venueSuggestionOutbox",
            "mediaProcessing", "mediaCleanup", "mediaWorker", "storage", "mobileErrors");
    public static final Set<String> HEALTH_METRICS = Set.of("soundconnect.health.status", "soundconnect.health.observed",
            "soundconnect.health.measurement.age", "soundconnect.health.pending", "soundconnect.health.inflight",
            "soundconnect.health.deadletter", "soundconnect.health.suppressed", "soundconnect.health.retry",
            "soundconnect.health.publishing", "soundconnect.health.review", "soundconnect.health.stale",
            "soundconnect.health.provider.accepted", "soundconnect.health.oldest.pending.age",
            "soundconnect.health.ready.messages", "soundconnect.health.consumers", "soundconnect.health.failed",
            "soundconnect.health.request.count", "soundconnect.health.server.error.count",
            "soundconnect.health.server.error.rate", "soundconnect.health.mean.latency", "soundconnect.health.window",
            "soundconnect.health.redis.used.memory", "soundconnect.health.redis.max.memory", "soundconnect.health.redis.evicted.keys",
            "soundconnect.health.redis.connected.clients", "soundconnect.health.redis.blocked.clients", "soundconnect.health.redis.rejected.connections",
            "soundconnect.health.redis.total.connections.received", "soundconnect.health.redis.memory.fragmentation.ratio", "soundconnect.health.redis.info.latency",
            "soundconnect.health.postgres.sessions", "soundconnect.health.postgres.active.sessions",
            "soundconnect.health.postgres.idle.in.transaction.sessions", "soundconnect.health.postgres.slow.active.sessions",
            "soundconnect.health.postgres.oldest.active.age", "soundconnect.health.postgres.waiting.locks",
            "soundconnect.health.postgres.wait.start.known", "soundconnect.health.postgres.oldest.known.wait.age",
            "soundconnect.health.postgres.database.deadlocks", "soundconnect.health.postgres.database.stats.reset.known",
            "soundconnect.health.postgres.database.stats.reset.epoch",
            "soundconnect.health.postgres.database.postmaster.start.epoch",
            "soundconnect.health.postgres.statement.groups", "soundconnect.health.postgres.statement.calls",
            "soundconnect.health.postgres.statement.execution.total", "soundconnect.health.postgres.statement.execution.maximum",
            "soundconnect.health.postgres.statement.slow.mean.groups", "soundconnect.health.postgres.statement.blocks.read",
            "soundconnect.health.postgres.statement.blocks.hit", "soundconnect.health.postgres.statement.groups.deallocated",
            "soundconnect.health.postgres.statement.stats.reset");
    private static final Set<String> STATUSES = Set.of("UP", "DEGRADED", "DOWN", "UNKNOWN", "DISABLED", "STALE");
    private static final Set<String> POOLS = Set.of("G1 Eden Space", "G1 Old Gen", "G1 Survivor Space", "Metaspace",
            "Compressed Class Space", "CodeHeap 'non-nmethods'", "CodeHeap 'profiled nmethods'", "CodeHeap 'non-profiled nmethods'",
            "Eden Space", "Survivor Space", "Tenured Gen", "PS Eden Space", "PS Survivor Space", "PS Old Gen",
            "ZHeap", "Shenandoah", "CodeCache");
    private static final Set<String> GC_CAUSES = Set.of("G1 Evacuation Pause", "G1 Humongous Allocation", "G1 Preventive Collection",
            "Metadata GC Threshold", "Metadata GC Clear Soft References", "System.gc()", "Allocation Failure", "No GC", "Ergonomics");
    private static final Set<String> UNITS = Set.of("bytes", "seconds", "milliseconds", "nanoseconds", "connections", "threads",
            "classes", "files", "percent", "messages", "requests");
    private final int maxSeries;
    private final Set<Meter.Id> accepted = new HashSet<>();
    private int wireSeries;
    private long denied;

    public TelemetryExportMeterFilter(int maxSeries) {
        if (maxSeries < 1 || maxSeries > 2048) throw new IllegalArgumentException("Invalid export series bound");
        this.maxSeries = maxSeries;
    }

    @Override
    public Meter.Id map(Meter.Id id) {
        String name = id.getName();
        List<Tag> tags = new ArrayList<>();
        if (name.equals("http.server.requests")) {
            tags.add(Tag.of("uri", routeGroup(id.getTag("uri"))));
            tags.add(Tag.of("method", fixed(id.getTag("method"), Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"), "OTHER")));
            String status = id.getTag("status");
            tags.add(Tag.of("status", status != null && (status.matches("[1-5][0-9]{2}") || status.matches("[1-5]xx")) ? status.charAt(0) + "xx" : "other"));
        } else if (AUTH_METRICS.contains(name)) {
            tags.add(Tag.of("dimension", fixed(id.getTag("dimension"), AUTH_DIMENSIONS, "other")));
            tags.add(Tag.of("decision", fixed(id.getTag("decision"), AUTH_DECISIONS, "other")));
        } else if (WORKER_METRICS.contains(name)) {
            if (name.equals("soundconnect.worker.job.duration") || name.equals("soundconnect.worker.job.active") || name.equals("soundconnect.worker.admission"))
                tags.add(Tag.of("workflow", fixed(id.getTag("workflow"), WORKFLOWS, "other")));
            if (!name.equals("soundconnect.worker.job.active") && !name.equals("soundconnect.worker.temp.reserved") && !name.equals("soundconnect.worker.temp.limit") && !name.startsWith("soundconnect.worker.ffmpeg."))
                tags.add(Tag.of("outcome", fixed(id.getTag("outcome"), WORKER_OUTCOMES, "other")));
        } else if (name.startsWith("soundconnect.scheduler.") && BACKGROUND_METRICS.contains(name)) {
            tags.add(Tag.of("schedule", fixed(id.getTag("schedule"), SCHEDULES, "other")));
            if (name.equals("soundconnect.scheduler.duration")) tags.add(Tag.of("outcome", fixed(id.getTag("outcome"), SCHEDULER_OUTCOMES, "other")));
        } else if (DEPENDENCY_METRICS.contains(name)) {
            tags.add(Tag.of("provider", fixed(id.getTag("provider"), DEPENDENCY_PROVIDERS, "other")));
            tags.add(Tag.of("operation", fixed(id.getTag("operation"), DEPENDENCY_OPERATIONS, "other")));
            tags.add(Tag.of("result", fixed(id.getTag("result"), DEPENDENCY_RESULTS, "other")));
        } else if (HEALTH_METRICS.contains(name)) {
            tags.add(Tag.of("component", fixed(id.getTag("component"), COMPONENTS, "unknown")));
            if (name.equals("soundconnect.health.status")) tags.add(Tag.of("status", fixed(id.getTag("status"), STATUSES, "UNKNOWN")));
        } else if (name.startsWith("jvm.memory.")) {
            tags.add(Tag.of("area", fixed(id.getTag("area"), Set.of("heap", "nonheap"), "other")));
            tags.add(Tag.of("id", fixed(id.getTag("id"), POOLS, "other")));
        } else if (name.startsWith("jvm.buffer.")) {
            tags.add(Tag.of("id", fixed(id.getTag("id"), Set.of("direct", "mapped", "mapped - 'non-volatile memory'"), "other")));
        } else if (name.equals("jvm.threads.states")) {
            tags.add(Tag.of("state", fixed(id.getTag("state"), Set.of("new", "runnable", "blocked", "waiting", "timed-waiting", "terminated"), "other")));
        } else if (name.equals("jvm.gc.pause") || name.equals("jvm.gc.concurrent.phase.time")) {
            tags.add(Tag.of("action", fixed(id.getTag("action"), Set.of("end of minor GC", "end of major GC", "end of GC pause", "end of concurrent GC"), "other")));
            tags.add(Tag.of("cause", fixed(id.getTag("cause"), GC_CAUSES, "other")));
        } else if (name.startsWith("hikaricp.")) {
            // Each runtime owns one namespaced pool. Never export configured pool names.
            tags.add(Tag.of("pool", "primary"));
        }
        return new Meter.Id(name, Tags.of(tags), UNITS.contains(id.getBaseUnit() == null ? "" : id.getBaseUnit())
                ? id.getBaseUnit() : null, null, id.getType());
    }

    @Override
    public synchronized MeterFilterReply accept(Meter.Id id) {
        if (!canExport(id)) return deny();
        if (accepted.contains(id)) return MeterFilterReply.NEUTRAL;
        int cost = wireCost(id);
        if (wireSeries + cost > maxSeries) return deny();
        accepted.add(id);
        wireSeries += cost;
        return MeterFilterReply.NEUTRAL;
    }

    /** Stateless shared contract: the encoder must apply the same final-ID policy as registration. */
    boolean canExport(Meter.Id id) {
        if (!RUNTIME_METRICS.contains(id.getName()) && !HEALTH_METRICS.contains(id.getName()) && !EXPORT_METRICS.contains(id.getName())
                && !WORKER_METRICS.contains(id.getName()) && !BACKGROUND_METRICS.contains(id.getName()) && !DEPENDENCY_METRICS.contains(id.getName()) && !AUTH_METRICS.contains(id.getName()) && !WEBSOCKET_METRICS.contains(id.getName())) return false;
        if (HEALTH_METRICS.contains(id.getName()) && !COMPONENTS.contains(id.getTag("component") == null ? "" : id.getTag("component"))) return false;
        if (id.getName().startsWith("soundconnect.health.postgres.")) {
            String component = id.getName().startsWith("soundconnect.health.postgres.statement.") ? "databaseStatements" : "databaseSessions";
            if (!component.equals(id.getTag("component")) || id.getType() != Meter.Type.GAUGE) return false;
        }
        if (WORKER_METRICS.contains(id.getName()) && ("other".equals(id.getTag("workflow")) || "other".equals(id.getTag("outcome")))) return false;
        if (BACKGROUND_METRICS.contains(id.getName()) && ("other".equals(id.getTag("schedule")) || "other".equals(id.getTag("outcome")))) return false;
        if (DEPENDENCY_METRICS.contains(id.getName()) && !safeDependency(id)) return false;
        if (AUTH_METRICS.contains(id.getName()) && (!AUTH_DIMENSIONS.contains(id.getTag("dimension") == null ? "" : id.getTag("dimension")) || !AUTH_DECISIONS.contains(id.getTag("decision") == null ? "" : id.getTag("decision")))) return false;
        // accept sees the final mapped ID, including filters Boot/customizers append later.
        // Fail closed if a later common-tag filter reintroduces identifiers or secrets.
        return safeFinalId(id);
    }

    public synchronized int usedWireSeries() { return wireSeries; }
    public synchronized long deniedMeters() { return denied; }
    public int maxWireSeries() { return maxSeries; }

    private MeterFilterReply deny() { denied++; return MeterFilterReply.DENY; }

    boolean safeFinalId(Meter.Id id) {
        Meter.Id sanitized = map(id);
        return id.getTags().equals(sanitized.getTags()) && java.util.Objects.equals(id.getBaseUnit(), sanitized.getBaseUnit())
                && id.getDescription() == null;
    }

    static int wireCost(Meter.Id id) {
        // Conservatively reserve histogram space for every allowed timer/summary,
        // including binders which may supply their own distribution configuration.
        return switch (id.getType()) {
            case TIMER, DISTRIBUTION_SUMMARY, LONG_TASK_TIMER -> HISTOGRAM_WIRE_SERIES;
            default -> 1;
        };
    }

    @Override
    public DistributionStatisticConfig configure(Meter.Id id, DistributionStatisticConfig original) {
        if (id.getType() != Meter.Type.TIMER && id.getType() != Meter.Type.DISTRIBUTION_SUMMARY
                && id.getType() != Meter.Type.LONG_TASK_TIMER) return original;
        return boundedDistribution(id.getName(), original);
    }

    static DistributionStatisticConfig boundedDistribution(String name, DistributionStatisticConfig original) {
        // Explicit finite buckets bound cost and support aggregation for p95/p99 in Grafana.
        double[] seconds = switch (name) {
            case "soundconnect.worker.job.duration" -> new double[]{1, 5, 10, 30, 60, 120, 300, 600, 1800, 3600};
            case "soundconnect.worker.temp.wait" -> new double[]{.001, .01, .1, 1, 5, 10, 30, 60, 120, 300};
            case "soundconnect.dependency.duration", "soundconnect.scheduler.duration" -> new double[]{.01, .1, .5, 1, 5, 10, 30, 60, 120, 300};
            case "soundconnect.auth.ratelimit.duration" -> new double[]{.001, .005, .01, .025, .05, .1, .25, .5, 1, 3};
            default -> new double[]{.005, .01, .025, .1, .25, .5, 1, 2.5, 10, 30};
        };
        double[] nanos = java.util.Arrays.stream(seconds).map(value -> value * 1_000_000_000).toArray();
        return DistributionStatisticConfig.builder().percentilesHistogram(false).percentiles()
                .serviceLevelObjectives(nanos).bufferLength(1).expiry(Duration.ofMinutes(5)).build().merge(original);
    }

    private static boolean safeDependency(Meter.Id id) {
        String provider = id.getTag("provider"), operation = id.getTag("operation"), result = id.getTag("result");
        if (provider == null || operation == null || result == null) return false;
        if (!DEPENDENCY_PROVIDERS.contains(provider) || !DEPENDENCY_OPERATIONS.contains(operation) || !DEPENDENCY_RESULTS.contains(result)) return false;
        return switch (provider) {
            case "mailersend", "fcm" -> operation.equals("send") && !result.equals("success");
            case "cloudfront" -> operation.equals("invalidate") && !result.equals("accepted");
            case "s3" -> !operation.equals("send") && !operation.equals("invalidate") && !result.equals("accepted");
            default -> false;
        };
    }

    static String routeGroup(String uri) {
        if (uri != null && ROUTE_GROUPS.contains(uri)) return uri;
        if (uri == null || uri.length() > 1024 || uri.indexOf('?') >= 0 || uri.indexOf('%') >= 0
                || uri.indexOf('@') >= 0 || uri.chars().anyMatch(Character::isISOControl)) return "other";
        if (uri.equals("/healthz") || uri.equals("/actuator") || uri.startsWith("/actuator/")
                || uri.equals("/api/v1/admin/system-health")) return "monitoring";
        if (!uri.startsWith("/api/v1/")) return "other";
        String segment = uri.substring(8).split("/", 2)[0];
        return switch (segment) {
            case "auth", "login", "register", "otp", "password-reset", "google", "oauth2" -> "auth";
            case "admin" -> "admin";
            case "media", "uploads" -> "media";
            case "profiles", "musicians", "listeners", "users" -> "profiles";
            case "feed", "feeds" -> "feed";
            case "notifications", "push" -> "notifications";
            case "messages", "conversations", "dm", "chat" -> "messaging";
            case "venues", "venue-suggestions" -> "venues";
            case "studios", "studio-rooms", "reservations" -> "studios";
            case "events" -> "events";
            case "table-groups" -> "table-groups";
            case "collabs", "collab" -> "collabs";
            case "marketplace" -> "marketplace";
            case "analytics" -> "analytics";
            case "cities", "districts", "neighborhoods", "instruments", "backline" -> "catalog";
            default -> "other";
        };
    }

    private static String fixed(String value, Set<String> allowed, String fallback) {
        return value != null && allowed.contains(value) ? value : fallback;
    }
}
