package com.berkayb.soundconnect.auth.ratelimit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Fixed-cardinality decision timing. Identities, keys and bucket names never enter this API. */
@Component
@ConditionalOnProperty(prefix = "app.telemetry.metrics", name = "enabled", havingValue = "true")
public final class AuthRateLimiterTelemetry {
    enum Dimension { IP, ACCOUNT }
    enum Outcome { PERMITTED, LIMITED, UNAVAILABLE, DISABLED }
    private record Meters(Counter decisions, Timer duration) { }
    private final Map<Dimension, Map<Outcome, Meters>> meters = new EnumMap<>(Dimension.class);

    public AuthRateLimiterTelemetry(MeterRegistry registry) {
        for (Dimension dimension : Dimension.values()) {
            var outcomes = new EnumMap<Outcome, Meters>(Outcome.class);
            for (Outcome outcome : Outcome.values()) {
                String[] tags = {"dimension", dimension.name().toLowerCase(Locale.ROOT),
                        "decision", outcome.name().toLowerCase(Locale.ROOT)};
                outcomes.put(outcome, new Meters(
                        Counter.builder("soundconnect.auth.ratelimit.decisions").tags(tags).register(registry),
                        Timer.builder("soundconnect.auth.ratelimit.duration").tags(tags).register(registry)));
            }
            meters.put(dimension, outcomes);
        }
    }

    void record(Dimension dimension, Outcome outcome, long elapsedNanos) {
        Meters value = meters.get(dimension).get(outcome);
        value.decisions.increment();
        value.duration.record(Math.max(0, elapsedNanos), TimeUnit.NANOSECONDS);
    }
}
