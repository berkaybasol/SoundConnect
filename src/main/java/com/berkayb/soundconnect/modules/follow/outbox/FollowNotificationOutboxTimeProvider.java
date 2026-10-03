package com.berkayb.soundconnect.modules.follow.outbox;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

@Component
public class FollowNotificationOutboxTimeProvider {
    private final Clock clock;

    public FollowNotificationOutboxTimeProvider() {
        this(Clock.systemUTC());
    }

    FollowNotificationOutboxTimeProvider(Clock clock) {
        this.clock = clock;
    }

    public Instant now() {
        return Instant.now(clock);
    }
}
