package com.berkayb.soundconnect.modules.media.telemetry;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.EnumMap;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/** Finite, process-local observations. Telemetry never owns a job, lease, retry or resource permit. */
@Component
public final class MediaWorkerMetrics {
    public enum Job { HLS, IMAGE_THUMBNAIL }
    public enum JobOutcome { SUCCESS, FAILURE, LEASE_LOST, FENCED, INTERRUPTED }
    public enum Admission { ACCEPTED, SATURATED, REJECTED }
    public enum TempOutcome { ACQUIRED, TIMEOUT, INTERRUPTED }
    public enum LeaseOutcome { RENEWED, LOST }
    public enum Recovery { RETRY_CLEANUP, TERMINAL_CLEANUP, STALE_SENT }

    private static final MediaWorkerMetrics NOOP = new MediaWorkerMetrics(null);
    private final EnumMap<Job, AtomicInteger> active = new EnumMap<>(Job.class);
    private final EnumMap<Job, EnumMap<JobOutcome, Timer>> durations = new EnumMap<>(Job.class);
    private final EnumMap<Job, EnumMap<Admission, Counter>> admissions = new EnumMap<>(Job.class);
    private final EnumMap<TempOutcome, Timer> waits = new EnumMap<>(TempOutcome.class);
    private final EnumMap<LeaseOutcome, Counter> leases = new EnumMap<>(LeaseOutcome.class);
    private final EnumMap<Recovery, Counter> recoveries = new EnumMap<>(Recovery.class);
    private volatile LongSupplier reservedBytes;
    private volatile double tempLimit = Double.NaN;
    private final FfmpegCpuState ffmpeg;

    @Autowired
    public MediaWorkerMetrics(MeterRegistry registry) {
        this(registry, System::nanoTime);
    }

    MediaWorkerMetrics(MeterRegistry registry, LongSupplier nanoTime) {
        ffmpeg = new FfmpegCpuState(nanoTime);
        for (Job job : Job.values()) {
            var count = new AtomicInteger();
            active.put(job, count);
            var timers = new EnumMap<JobOutcome, Timer>(JobOutcome.class);
            durations.put(job, timers);
            var counters = new EnumMap<Admission, Counter>(Admission.class);
            admissions.put(job, counters);
            if (registry == null) continue;
            safely(() -> Gauge.builder("soundconnect.worker.job.active", count, AtomicInteger::doubleValue)
                    .tag("workflow", value(job)).strongReference(true).register(registry));
            for (JobOutcome outcome : JobOutcome.values()) safely(() -> timers.put(outcome,
                    registry.timer("soundconnect.worker.job.duration", "workflow", value(job), "outcome", value(outcome))));
            for (Admission outcome : Admission.values()) safely(() -> counters.put(outcome,
                    registry.counter("soundconnect.worker.admission", "workflow", value(job), "outcome", value(outcome))));
        }
        if (registry == null) return;
        for (TempOutcome outcome : TempOutcome.values()) safely(() -> waits.put(outcome,
                registry.timer("soundconnect.worker.temp.wait", "outcome", value(outcome))));
        for (LeaseOutcome outcome : LeaseOutcome.values()) safely(() -> leases.put(outcome,
                registry.counter("soundconnect.worker.lease", "outcome", value(outcome))));
        for (Recovery outcome : Recovery.values()) safely(() -> recoveries.put(outcome,
                registry.counter("soundconnect.worker.recovery", "outcome", value(outcome))));
        safely(() -> Gauge.builder("soundconnect.worker.temp.reserved", this, MediaWorkerMetrics::reserved)
                .baseUnit("bytes").strongReference(true).register(registry));
        safely(() -> Gauge.builder("soundconnect.worker.temp.limit", this, metrics -> metrics.tempLimit)
                .baseUnit("bytes").strongReference(true).register(registry));
        safely(() -> Gauge.builder("soundconnect.worker.ffmpeg.active", ffmpeg, FfmpegCpuState::active)
                .strongReference(true).register(registry));
        safely(() -> Gauge.builder("soundconnect.worker.ffmpeg.cpu.time", ffmpeg, FfmpegCpuState::cpuSeconds)
                .baseUnit("seconds").strongReference(true).register(registry));
    }

    public static MediaWorkerMetrics noop() { return NOOP; }

    public JobSample startJob(Job job) {
        active.get(job).incrementAndGet();
        return new JobSample(this, job, System.nanoTime());
    }

    public void admission(Job job, Admission outcome) { increment(admissions.get(job).get(outcome), 1); }
    public void lease(LeaseOutcome outcome) { increment(leases.get(outcome), 1); }

    /** Observe committed recovery rows, not a transaction that may still roll back. */
    public void recovered(Recovery outcome, int rows) {
        if (rows <= 0) return;
        safely(() -> {
            if (TransactionSynchronizationManager.isSynchronizationActive()
                    && TransactionSynchronizationManager.isActualTransactionActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCommit() { increment(recoveries.get(outcome), rows); }
                });
            } else increment(recoveries.get(outcome), rows);
        });
    }

    public void bindTempBudget(LongSupplier reservedBytes, long limit) {
        this.reservedBytes = reservedBytes;
        this.tempLimit = limit;
    }

    public void tempWait(TempOutcome outcome, long startedNanos) { record(waits.get(outcome), startedNanos); }

    /** Only the caller-owned child is read, at the workflow's existing polling points. */
    public FfmpegSample startFfmpeg(Process child) {
        var sample = new FfmpegSample(ffmpeg, child);
        sample.poll();
        return sample;
    }

    private double reserved() {
        LongSupplier read = reservedBytes;
        if (read == null) return Double.NaN;
        try { return read.getAsLong(); }
        catch (RuntimeException unavailable) { return Double.NaN; }
    }

    private static void record(Timer timer, long start) {
        if (timer != null) safely(() -> timer.record(Math.max(0, System.nanoTime() - start), TimeUnit.NANOSECONDS));
    }
    private static void increment(Counter counter, int count) { if (counter != null) safely(() -> counter.increment(count)); }
    private static void safely(Runnable action) { try { action.run(); } catch (RuntimeException ignored) { /* No product or log side effect. */ } }
    private static String value(Enum<?> value) { return value.name().toLowerCase(Locale.ROOT); }

    private static final class FfmpegCpuState {
        private static final long MAX_SAMPLE_AGE_NANOS = TimeUnit.SECONDS.toNanos(5);
        private final LongSupplier nanoTime;
        private final ArrayList<FfmpegSample> samples = new ArrayList<>(32);
        private int active;
        private FfmpegCpuState(LongSupplier nanoTime) { this.nanoTime = nanoTime; }
        synchronized double active() { return active; }
        synchronized double cpuSeconds() {
            if (active == 0 || samples.size() != active) return Double.NaN;
            long now = nanoTime.getAsLong();
            double total = 0;
            for (var sample : samples) {
                long age = now - sample.sampledAt;
                if (!Double.isFinite(sample.previous) || age < 0 || age > MAX_SAMPLE_AGE_NANOS) return Double.NaN;
                total += sample.previous;
            }
            return total;
        }
    }

    public static final class FfmpegSample implements AutoCloseable {
        private final FfmpegCpuState state;
        private final Process child;
        private double previous = Double.NaN;
        private long sampledAt;
        private boolean closed;
        private FfmpegSample(FfmpegCpuState state, Process child) {
            this.state = state; this.child = child;
            synchronized (state) {
                state.active++;
                // Product admission is separate; an unexpected extra child makes CPU unknown.
                if (state.samples.size() < 32) state.samples.add(this);
            }
        }
        public void poll() {
            synchronized (state) { if (closed) return; }
            double measured = Double.NaN;
            try {
                var duration = child.info().totalCpuDuration();
                if (duration.isPresent() && !duration.get().isNegative()) measured = duration.get().toNanos() / 1_000_000_000d;
            } catch (RuntimeException unavailable) { /* Platform/process lifetime may hide CPU metadata. */ }
            synchronized (state) {
                if (closed) return;
                previous = measured;
                sampledAt = state.nanoTime.getAsLong();
            }
        }
        @Override public void close() {
            synchronized (state) {
                if (closed) return;
                closed = true;
                state.active--;
                state.samples.remove(this);
            }
        }
    }

    public static final class JobSample {
        private final MediaWorkerMetrics owner;
        private final Job job;
        private final long startedNanos;
        private final AtomicBoolean finished = new AtomicBoolean();
        private JobSample(MediaWorkerMetrics owner, Job job, long startedNanos) {
            this.owner = owner; this.job = job; this.startedNanos = startedNanos;
        }
        public void finish(JobOutcome outcome) {
            if (!finished.compareAndSet(false, true)) return;
            owner.active.get(job).decrementAndGet();
            record(owner.durations.get(job).get(outcome), startedNanos);
        }
    }
}
