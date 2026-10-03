package com.berkayb.soundconnect.modules.notification.push.transport;

import java.time.Instant;
import java.util.Map;

/** Immutable transport input. Never expose registration identifiers or content in logs. */
public record PushEnvelope(String token, String title, String body, Map<String, String> data,
                           String collapseKey, Instant expiresAt) {
    public PushEnvelope {
        data = data == null ? Map.of() : Map.copyOf(data);
    }

    @Override
    public String toString() {
        return "PushEnvelope[redacted]";
    }
}
