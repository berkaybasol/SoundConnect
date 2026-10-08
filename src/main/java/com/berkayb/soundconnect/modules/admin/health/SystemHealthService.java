package com.berkayb.soundconnect.modules.admin.health;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.berkayb.soundconnect.modules.admin.health.SystemHealthProbe.Reading;
import static com.berkayb.soundconnect.modules.admin.health.SystemHealthSnapshot.*;

/** The HTTP read path never runs probes. One bounded background generation owns all observations. */
@Service
public class SystemHealthService implements AutoCloseable {
    private final List<Slot> slots;
    private final Map<String, Reading> readings = new ConcurrentHashMap<>();
    private final SystemHealthProperties properties;
    private final Clock clock;
    private final boolean backgroundEnabled;
    private boolean started;
    private boolean closed;
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(32), task -> {
                Thread thread = new Thread(task, "system-health-probe"); thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "system-health-refresh"); thread.setDaemon(true); return thread;
    });
    private record Slot(SystemHealthProbe probe, AtomicInteger state) { }

    @Autowired
    public SystemHealthService(SystemHealthSources sources, SystemHealthProperties properties, Environment environment) {
        this(sources.probes(), properties, Clock.systemUTC(), !environment.acceptsProfiles(Profiles.of("test")));
    }

    SystemHealthService(List<SystemHealthProbe> probes, SystemHealthProperties properties, Clock clock, boolean backgroundEnabled) {
        if (probes.size() > 32) throw new IllegalArgumentException("Health source limit exceeded");
        this.slots = probes.stream().map(probe -> new Slot(probe, new AtomicInteger())).toList();
        this.properties = properties; this.clock = clock; this.backgroundEnabled = backgroundEnabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    synchronized void start() {
        // Probes resolve lazy Spring singletons. Never race singleton construction
        // from @PostConstruct, including when application startup is slow.
        if (!backgroundEnabled || started || closed) return;
        started = true;
        scheduler.scheduleWithFixedDelay(this::refreshSafely, 0,
                properties.getRefreshIntervalSeconds(), TimeUnit.SECONDS);
    }

    private void refreshSafely() {
        try { refresh(); }
        catch (RuntimeException ignored) {
            // Do not log exception content. Existing observations age naturally if collection fails.
        }
    }

    void refresh() {
        if (!refreshing.compareAndSet(false, true)) return;
        Map<Slot, Future<Reading>> futures = new LinkedHashMap<>();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(properties.getProbeTimeoutMillis());
        try {
            for (Slot slot : slots) {
                if (!slot.state.compareAndSet(0, 1)) { failed(slot, ReasonCode.PROBE_BUSY); continue; }
                try {
                    futures.put(slot, workers.submit(() -> {
                        if (!slot.state.compareAndSet(1, 2)) return null;
                        try { return slot.probe.read().get(); }
                        finally { slot.state.set(0); }
                    }));
                } catch (RejectedExecutionException unavailable) {
                    slot.state.compareAndSet(1, 0);
                    failed(slot, ReasonCode.PROBE_BUSY);
                }
            }
            for (var entry : futures.entrySet()) {
                Slot slot = entry.getKey(); Future<Reading> future = entry.getValue();
                try {
                    Reading reading = future.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                    if (reading == null) failed(slot, ReasonCode.INVALID_MEASUREMENT);
                    else if (reading.measuredAt() == null) failed(slot, reading.reasonCode());
                    else readings.put(slot.probe.id(), reading);
                } catch (TimeoutException timeout) {
                    future.cancel(true);
                    // A stuck running probe remains state=2 until it really returns. No duplicate work is queued.
                    slot.state.compareAndSet(1, 0);
                    failed(slot, ReasonCode.PROBE_TIMEOUT);
                } catch (ExecutionException failure) {
                    failed(slot, ReasonCode.PROBE_FAILED);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    failed(slot, ReasonCode.PROBE_FAILED);
                    return;
                }
            }
        } finally {
            futures.forEach((slot, future) -> {
                if (!future.isDone()) { future.cancel(true); slot.state.compareAndSet(1, 0); }
            });
            workers.purge();
            refreshing.set(false);
        }
    }

    private void failed(Slot slot, ReasonCode reason) {
        readings.compute(slot.probe.id(), (key, before) -> new Reading(Status.UNKNOWN, reason,
                before == null ? null : before.measuredAt(), before == null ? Map.of() : before.metrics()));
    }

    public SystemHealthSnapshot snapshot() {
        var now = clock.instant();
        List<SystemHealthSnapshot.Component> result = new ArrayList<>();
        for (Slot slot : slots) {
            Reading reading = readings.getOrDefault(slot.probe.id(), Reading.unknown(ReasonCode.NOT_MEASURED));
            Long age = reading.measuredAt() == null ? null : Math.max(0, Duration.between(reading.measuredAt(), now).getSeconds());
            Status status = reading.status(); ReasonCode reason = reading.reasonCode();
            if (reading.measuredAt() != null && reading.measuredAt().isAfter(now)) {
                status = Status.UNKNOWN; reason = ReasonCode.INVALID_MEASUREMENT;
            } else if (age != null && age > properties.getStaleAfterSeconds()) {
                status = Status.STALE; reason = ReasonCode.MEASUREMENT_STALE;
            }
            result.add(new SystemHealthSnapshot.Component(slot.probe.id(), slot.probe.label(), status,
                    reading.measuredAt(), age, reason, slot.probe.userImpact(), reading.metrics()));
        }
        return new SystemHealthSnapshot(aggregate(result.stream().map(SystemHealthSnapshot.Component::status).toList()),
                now, properties.getRefreshIntervalSeconds(), properties.getStaleAfterSeconds(), result);
    }

    static Status aggregate(Collection<Status> statuses) {
        for (Status status : List.of(Status.DOWN, Status.DEGRADED, Status.STALE, Status.UNKNOWN))
            if (statuses.contains(status)) return status;
        if (statuses.contains(Status.UP)) return Status.UP;
        return statuses.isEmpty() ? Status.UNKNOWN : Status.DISABLED;
    }

    @Override @PreDestroy
    public synchronized void close() {
        if (closed) return;
        closed = true;
        scheduler.shutdownNow(); workers.shutdownNow();
    }
}
