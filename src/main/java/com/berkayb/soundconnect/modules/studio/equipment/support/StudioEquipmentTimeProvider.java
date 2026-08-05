package com.berkayb.soundconnect.modules.studio.equipment.support;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DateTimeException;
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
        ZoneId zone = DEFAULT_STUDIO_ZONE;
        if (studioTimeZone != null && !studioTimeZone.isBlank()) {
            try {
                zone = ZoneId.of(studioTimeZone);
            } catch (DateTimeException ignored) {
                // Match reservation behavior for legacy/corrupt persisted data;
                // profile writes still reject invalid IANA identifiers.
            }
        }
        return LocalDate.now(clock.withZone(zone));
    }
}
