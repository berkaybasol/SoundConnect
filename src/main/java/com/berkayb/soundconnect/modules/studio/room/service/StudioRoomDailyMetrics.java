package com.berkayb.soundconnect.modules.studio.room.service;

import com.berkayb.soundconnect.modules.studio.room.enums.StudioRoomAvailabilityStatus;

import java.time.LocalDate;

public record StudioRoomDailyMetrics(
        LocalDate localDate,
        long reservationCount,
        int occupiedHours,
        int availableHours,
        StudioRoomAvailabilityStatus availabilityStatus
) {
}
