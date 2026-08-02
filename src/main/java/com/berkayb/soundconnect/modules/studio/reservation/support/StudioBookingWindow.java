package com.berkayb.soundconnect.modules.studio.reservation.support;

import java.time.Instant;

public record StudioBookingWindow(Instant startsAt, Instant endsAt) {
}
