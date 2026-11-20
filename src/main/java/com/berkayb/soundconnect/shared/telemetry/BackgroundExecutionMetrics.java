package com.berkayb.soundconnect.shared.telemetry;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Passive observations of existing scheduler calls, never another scheduler or a retry mechanism. */
@Component
@ConditionalOnProperty(prefix = "app.telemetry.metrics", name = "enabled", havingValue = "true")
public final class BackgroundExecutionMetrics {
    public enum Schedule { follow_outbox, table_outbox, collab_outbox, overthinking_outbox, event_outbox, studio_outbox, campaigns }
    private static final BackgroundExecutionMetrics NOOP = new BackgroundExecutionMetrics(null);
    private final MeterRegistry registry;
    private final Map<Schedule, State> states = new EnumMap<>(Schedule.class);

    @Autowired
    public BackgroundExecutionMetrics(MeterRegistry registry) {
        this.registry = registry;
        for (Schedule schedule : Schedule.values()) {
            State state = new State();
            states.put(schedule, state);
            if (registry != null) safely(() -> {
                Gauge.builder("soundconnect.scheduler.active", state, s -> s.active.get())
                        .tag("schedule", schedule.name()).strongReference(true).register(registry);
                Gauge.builder("soundconnect.scheduler.last.started", state, s -> s.started)
                        .tag("schedule", schedule.name()).baseUnit("seconds").strongReference(true).register(registry);
                Gauge.builder("soundconnect.scheduler.last.completed", state, s -> s.completed)
                        .tag("schedule", schedule.name()).baseUnit("seconds").strongReference(true).register(registry);
            });
        }
    }

    public static BackgroundExecutionMetrics noop() { return NOOP; }

    public void observe(Schedule schedule, Runnable action) {
        if (registry == null) { action.run(); return; }
        State state = states.get(schedule);
        long start = System.nanoTime();
        state.started = System.currentTimeMillis() / 1000d;
        state.active.incrementAndGet();
        boolean completed = false;
        try { action.run(); completed = true; }
        finally {
            state.active.decrementAndGet();
            state.completed = System.currentTimeMillis() / 1000d;
            String outcome = completed ? "completed" : "failed";
            safely(() -> registry.timer("soundconnect.scheduler.duration", "schedule", schedule.name(), "outcome", outcome)
                    .record(Math.max(0, System.nanoTime() - start), TimeUnit.NANOSECONDS));
        }
    }

    /** A skipped delivery attempt, not a count of unique events or committed business transactions. */
    public void duplicateNotificationAttempt() {
        if (registry != null) safely(() -> registry.counter("soundconnect.notification.duplicate.attempts").increment());
    }

    private static void safely(Runnable write) {
        try { write.run(); } catch (RuntimeException ignored) { /* Observation cannot change product behavior. */ }
    }

    private static final class State {
        final AtomicInteger active = new AtomicInteger();
        volatile double started = Double.NaN;
        volatile double completed = Double.NaN;
    }
}
