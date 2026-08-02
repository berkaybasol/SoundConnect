package com.berkayb.soundconnect.modules.studio.equipment.support;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

@Component
public class StudioEquipmentTimeProvider {
    private static final ZoneId DEFAULT_STUDIO_ZONE = ZoneId.of("Europe/Istanbul");
    private final Clock clock;

    public StudioEquipmentTimeProvider() {
        this(Clock.systemUTC());
    }

    StudioEquipmentTimeProvider(Clock clock) {
        this.clock = clock;
    }

    public Instant now() {
        return clock.instant();
    }

    public LocalDate today(String studioTimeZone) {
        ZoneId zone = studioTimeZone == null || studioTimeZone.isBlank()
                ? DEFAULT_STUDIO_ZONE
                : ZoneId.of(studioTimeZone);
        return LocalDate.now(clock.withZone(zone));
    }
}
