package com.berkayb.soundconnect.modules.event.support;

import com.berkayb.soundconnect.modules.event.entity.Event;
import org.springframework.stereotype.Component;
import java.time.*;

/** Event wall-clock schedules are stored in Turkey time, independent of host timezone. */
@Component
public class EventScheduleClock {
    public static final ZoneId ZONE = ZoneId.of("Europe/Istanbul");
    public Instant instant() { return Instant.now(); }
    public LocalDateTime localNow() { return LocalDateTime.ofInstant(instant(), ZONE); }
    public Instant startsAt(Event event) {
        if (event.getEventDate() == null || event.getStartTime() == null) return null;
        return event.getEventDate().atTime(event.getStartTime()).atZone(ZONE).toInstant();
    }
}
