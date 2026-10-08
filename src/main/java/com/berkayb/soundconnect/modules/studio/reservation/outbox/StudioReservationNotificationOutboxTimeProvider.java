package com.berkayb.soundconnect.modules.studio.reservation.outbox;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

@Component
public class StudioReservationNotificationOutboxTimeProvider {
    private final Clock clock;

    public StudioReservationNotificationOutboxTimeProvider() {
        this(Clock.systemUTC());
    }

    StudioReservationNotificationOutboxTimeProvider(Clock clock) {
        this.clock = clock;
    }

    public Instant now() {
        return Instant.now(clock);
    }
}
