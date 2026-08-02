package com.berkayb.soundconnect.modules.studio.reservation.support;

import java.time.Instant;
import java.time.LocalDate;

public record StudioDateRange(LocalDate from, LocalDate to, Instant startsAt, Instant endsAt) {
}
