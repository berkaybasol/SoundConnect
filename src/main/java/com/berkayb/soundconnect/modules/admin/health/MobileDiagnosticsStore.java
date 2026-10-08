package com.berkayb.soundconnect.modules.admin.health;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.berkayb.soundconnect.modules.admin.health.SystemHealthProbe.Reading;
import static com.berkayb.soundconnect.modules.admin.health.SystemHealthSnapshot.*;

/** Durable bounded anonymous records. Actor identity is used only for the transaction's session fence. */
@Service
public class MobileDiagnosticsStore implements AutoCloseable {
    public record Receipt(UUID eventId, boolean accepted) { }
    public record Event(UUID eventId, Instant receivedAt, MobileDiagnosticRequest.Severity severity,
                        MobileDiagnosticRequest.Source source, MobileDiagnosticRequest.ErrorType errorType,
                        MobileDiagnosticRequest.Environment environment) { }
    enum Rejection { SESSION, CONFLICT, CAPACITY }
    static final class Rejected extends RuntimeException {
        final Rejection reason;
        Rejected(Rejection reason) { this.reason = reason; }
    }
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final MobileDiagnosticsProperties properties;
    private final ObjectMapper mapper;
    private final boolean backgroundEnabled;
    private boolean started;
    private boolean closed;
    private final ScheduledExecutorService retention = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "mobile-diagnostics-retention"); thread.setDaemon(true); return thread;
    });

    public MobileDiagnosticsStore(DataSource source, PlatformTransactionManager manager, MobileDiagnosticsProperties properties,
                                  ObjectMapper mapper, Environment environment) {
        jdbc = new JdbcTemplate(source); jdbc.setQueryTimeout(2); jdbc.setMaxRows(10);
        transaction = new TransactionTemplate(manager); transaction.setTimeout(2);
        this.properties = properties; this.mapper = mapper;
        backgroundEnabled = !environment.acceptsProfiles(Profiles.of("test"));
    }

    @EventListener(ApplicationReadyEvent.class)
    synchronized void start() {
        if (!backgroundEnabled || started || closed) return;
        started = true;
        retention.scheduleWithFixedDelay(() -> {
            try { if (properties.isEnabled()) cleanup(); }
            catch (RuntimeException ignored) { /* Health reports schema/storage availability without sensitive exceptions. */ }
        }, 60, 60, TimeUnit.SECONDS);
    }

    Receipt record(UUID actor, long authenticatedVersion, MobileDiagnosticRequest request) {
        String frames = String.join("\n", request.frames());
        String fingerprint = fingerprint(request);
        return transaction.execute(ignored -> {
            // Shared row lock conflicts with password reset's update lock and protects the commit boundary.
            List<Long> versions = jdbc.query("""
                    SELECT session_version FROM tbl_user WHERE id=? AND status='ACTIVE'
                      AND email_verified=true AND erased_at IS NULL FOR SHARE
                    """, (rs, row) -> rs.getLong(1), actor);
            if (versions.size() != 1 || versions.getFirst() != authenticatedVersion) throw new Rejected(Rejection.SESSION);
            if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(83107, 1)", Boolean.class)))
                throw new Rejected(Rejection.CAPACITY);
            List<String> existing = jdbc.query("SELECT fingerprint FROM tbl_mobile_diagnostic_event WHERE event_id=?",
                    (rs, row) -> rs.getString(1), request.eventId());
            if (!existing.isEmpty()) {
                if (!MessageDigest.isEqual(existing.getFirst().getBytes(StandardCharsets.US_ASCII), fingerprint.getBytes(StandardCharsets.US_ASCII)))
                    throw new Rejected(Rejection.CONFLICT);
                return new Receipt(request.eventId(), true);
            }
            Integer count = jdbc.queryForObject("SELECT count(*) FROM (SELECT 1 FROM tbl_mobile_diagnostic_event LIMIT ?) bounded_events",
                    Integer.class, properties.getMaxEvents());
            if (count == null || count >= properties.getMaxEvents()) throw new Rejected(Rejection.CAPACITY);
            jdbc.update("""
                    INSERT INTO tbl_mobile_diagnostic_event(event_id,fingerprint,severity,source,error_type,frames,environment,received_at)
                    VALUES(?,?,?,?,?,?,?,CURRENT_TIMESTAMP)
                    """, request.eventId(), fingerprint, request.severity().name(), request.source().name(),
                    request.errorType().name(), frames, request.environment().name());
            return new Receipt(request.eventId(), true);
        });
    }

    private String fingerprint(MobileDiagnosticRequest request) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(request)));
        } catch (NoSuchAlgorithmException | JsonProcessingException impossible) { throw new IllegalStateException("Diagnostic fingerprint unavailable"); }
    }

    void cleanup() {
        jdbc.update("""
                DELETE FROM tbl_mobile_diagnostic_event WHERE event_id IN (
                    SELECT event_id FROM tbl_mobile_diagnostic_event WHERE received_at < ?
                    ORDER BY received_at,event_id LIMIT 1000)
                """, Timestamp.from(Instant.now().minus(Duration.ofDays(properties.getRetentionDays()))));
    }

    List<Event> recent(int limit) {
        if (limit < 1 || limit > 10) throw new IllegalArgumentException("Invalid diagnostic page size");
        return jdbc.query("""
                SELECT event_id,received_at,severity,source,error_type,environment FROM tbl_mobile_diagnostic_event
                WHERE environment=? ORDER BY received_at DESC,event_id DESC LIMIT ?
                """, (rs, row) -> new Event(rs.getObject("event_id", UUID.class), rs.getTimestamp("received_at").toInstant(),
                MobileDiagnosticRequest.Severity.valueOf(rs.getString("severity")),
                MobileDiagnosticRequest.Source.valueOf(rs.getString("source")),
                MobileDiagnosticRequest.ErrorType.valueOf(rs.getString("error_type")),
                MobileDiagnosticRequest.Environment.valueOf(rs.getString("environment"))), properties.getEnvironment(), limit);
    }

    Reading health() {
        Instant now = Instant.now();
        if (!properties.isEnabled()) return new Reading(Status.DISABLED, ReasonCode.FEATURE_DISABLED, now, Map.of());
        return jdbc.query("""
                SELECT count(*) AS events,
                  count(*) FILTER (WHERE severity='FATAL') AS fatals,
                  count(*) FILTER (WHERE source='FRAME_TIMING') AS slow_frames,
                  max(received_at) AS last_event
                FROM tbl_mobile_diagnostic_event WHERE received_at >= ? AND environment=?
                """, rs -> {
            if (!rs.next()) return Reading.unknown(ReasonCode.PROBE_FAILED);
            long events = rs.getLong("events"), fatals = rs.getLong("fatals");
            Map<String, Double> values = new java.util.LinkedHashMap<>();
            values.put("eventsLast15Minutes", (double) events);
            values.put("fatalEventsLast15Minutes", (double) fatals);
            values.put("slowFramesLast15Minutes", (double) rs.getLong("slow_frames"));
            Timestamp last = rs.getTimestamp("last_event");
            if (last != null) values.put("lastEventAgeSeconds", (double) Math.max(0, Duration.between(last.toInstant(), now).getSeconds()));
            Status status = fatals > 0 || events >= properties.getErrorThreshold() ? Status.DEGRADED : Status.UP;
            return new Reading(status, SystemHealthSources.reason(status), now, values);
        }, Timestamp.from(now.minus(Duration.ofMinutes(15))), properties.getEnvironment());
    }

    @Override @PreDestroy public synchronized void close() {
        if (closed) return;
        closed = true;
        retention.shutdownNow();
    }
}
