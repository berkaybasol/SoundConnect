package com.berkayb.soundconnect.modules.collab.outbox;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

@Component
public class CollabNotificationOutboxTimeProvider {
    private final Clock clock;

    public CollabNotificationOutboxTimeProvider() {
        this(Clock.systemUTC());
    }

    CollabNotificationOutboxTimeProvider(Clock clock) {
        this.clock = clock;
    }

    public Instant now() {
        return Instant.now(clock);
    }
}
