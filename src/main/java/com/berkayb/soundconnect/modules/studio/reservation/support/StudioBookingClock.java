package com.berkayb.soundconnect.modules.studio.reservation.support;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * Authoritative booking-clock values expressed in the Studio's configured time zone.
 * A single snapshot prevents boundary fields in one response from being calculated
 * from different instants.
 */
public record StudioBookingClock(
        LocalDate todayLocalDate,
        LocalTime currentLocalTime,
        LocalDateTime latestBookableLocalDateTime
) {
}
