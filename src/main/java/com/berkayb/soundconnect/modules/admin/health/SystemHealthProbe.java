package com.berkayb.soundconnect.modules.admin.health;

import java.time.Instant;
import java.util.Map;
import java.util.function.Supplier;

import static com.berkayb.soundconnect.modules.admin.health.SystemHealthSnapshot.*;

record SystemHealthProbe(String id, String label, String userImpact, Supplier<Reading> read) {
    record Reading(Status status, ReasonCode reasonCode, Instant measuredAt, Map<String, Double> metrics) {
        Reading { metrics = Map.copyOf(metrics); }
        static Reading unknown(ReasonCode reason) {
            return new Reading(Status.UNKNOWN, reason, null, Map.of());
        }
    }
}
