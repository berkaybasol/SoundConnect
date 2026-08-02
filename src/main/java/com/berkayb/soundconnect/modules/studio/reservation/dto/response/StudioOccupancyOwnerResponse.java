package com.berkayb.soundconnect.modules.studio.reservation.dto.response;

import com.berkayb.soundconnect.modules.studio.reservation.enums.StudioOccupancyType;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

public record StudioOccupancyOwnerResponse(
        UUID id,
        UUID roomId,
        UUID reservationId,
        UUID clientRequestId,
        StudioOccupancyType type,
        Instant startsAt,
        Instant endsAt,
		LocalDate localDate,
		LocalTime localStartTime,
		LocalTime localEndTime,
        boolean active,
        long version
) {
}
