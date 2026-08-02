package com.berkayb.soundconnect.modules.studio.reservation.dto.response;

import com.berkayb.soundconnect.modules.studio.reservation.enums.StudioReservationStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

public record StudioReservationResponse(
        UUID id,
        UUID clientRequestId,
        UUID roomId,
        UUID studioProfileId,
        String roomName,
        Instant startsAt,
        Instant endsAt,
        String zoneId,
		LocalDate localDate,
		LocalTime localStartTime,
		LocalTime localEndTime,
        StudioReservationStatus status,
        boolean completed,
        boolean approvalRequired,
        Long hourlyPriceMinor,
        Long totalPriceMinor,
        String currency,
        long version
) {
}
