package com.berkayb.soundconnect.shared.telemetry;

import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.LongTaskTimer;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.micrometer.core.instrument.distribution.pause.PauseDetector;
import io.micrometer.registry.otlp.OtlpConfig;
import io.micrometer.registry.otlp.OtlpMeterRegistry;

import java.util.concurrent.ThreadFactory;

/** Keeps histogram cost bounded even when a later Boot filter supplies global distribution settings. */
final class BoundedOtlpMeterRegistry extends OtlpMeterRegistry {
    private static final ThreadFactory PUBLISHER = task -> {
        Thread thread = new Thread(task, "soundconnect-metrics-export");
        thread.setDaemon(true);
        return thread;
    };
    private final BoundedOtlpMetricsTransport transport;
    private final SafeOtlpMetricEncoder encoder;
    private volatile boolean ready;

    BoundedOtlpMeterRegistry(OtlpConfig config) {
        super(config, Clock.SYSTEM, PUBLISHER);
        this.encoder = new SafeOtlpMetricEncoder(config.resourceAttributes(), Clock.SYSTEM.wallTime() * 1_000_000);
        this.transport = new BoundedOtlpMetricsTransport(config.url());
        ready = true;
        start(PUBLISHER);
    }

    // The parent constructor calls start before subclass transport fields are initialized.
    @Override public void start(ThreadFactory factory) { if (ready) super.start(factory); }

    @Override protected void publish() {
        if (ready) transport.publish(encoder.encode(getMeters(), Clock.SYSTEM.wallTime() * 1_000_000));
    }

    long failedPublications() { return transport.failedPublications(); }
    long unsupportedMeters() { return encoder.unsupportedMeters(); }
    long lastSuccessEpochSeconds() { return transport.lastSuccessEpochSeconds(); }

    @Override public void close() {
        try { super.close(); }
        finally { ready = false; transport.close(); }
    }

    @Override protected Timer newTimer(Meter.Id id, DistributionStatisticConfig config, PauseDetector pauseDetector) {
        return super.newTimer(id, TelemetryExportMeterFilter.boundedDistribution(id.getName(), config), pauseDetector);
    }

    @Override protected DistributionSummary newDistributionSummary(Meter.Id id, DistributionStatisticConfig config, double scale) {
        return super.newDistributionSummary(id, TelemetryExportMeterFilter.boundedDistribution(id.getName(), config), scale);
    }

    @Override protected LongTaskTimer newLongTaskTimer(Meter.Id id, DistributionStatisticConfig config) {
        return super.newLongTaskTimer(id, TelemetryExportMeterFilter.boundedDistribution(id.getName(), config));
    }
}
