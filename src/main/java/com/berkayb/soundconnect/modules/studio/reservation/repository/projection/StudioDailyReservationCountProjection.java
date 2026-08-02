package com.berkayb.soundconnect.modules.studio.reservation.repository.projection;

import java.util.UUID;

public interface StudioDailyReservationCountProjection {
    UUID getRoomId();

    long getReservationCount();
}
