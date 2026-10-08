package com.berkayb.soundconnect.modules.admin.health;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Public contract: fixed component names and numeric metrics, never raw indicator details. */
public record SystemHealthSnapshot(Status status, Instant generatedAt, int refreshIntervalSeconds,
                                   int staleAfterSeconds, List<Component> components) {
    public enum Status { UP, DEGRADED, DOWN, UNKNOWN, DISABLED, STALE }
    public enum ReasonCode {
        HEALTHY, DEGRADED_SIGNAL, DEPENDENCY_UNAVAILABLE, FEATURE_DISABLED,
        NOT_CONFIGURED, NOT_MEASURED, PROBE_FAILED, PROBE_TIMEOUT, PROBE_BUSY,
        MEASUREMENT_STALE, NO_TRAFFIC, WARMING_UP, METRIC_LIMIT_EXCEEDED,
        INVALID_MEASUREMENT, EXTERNAL_OBSERVATION_REQUIRED
    }
    public record Component(String id, String label, Status status, Instant measuredAt,
                            Long ageSeconds, ReasonCode reasonCode, String userImpact,
                            Map<String, Double> metrics) {
        public Component { metrics = Map.copyOf(metrics); }
    }
    public SystemHealthSnapshot { components = List.copyOf(components); }
}
