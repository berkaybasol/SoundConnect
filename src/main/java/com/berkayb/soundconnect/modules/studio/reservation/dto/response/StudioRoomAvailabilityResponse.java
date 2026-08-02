package com.berkayb.soundconnect.modules.studio.reservation.dto.response;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

public record StudioRoomAvailabilityResponse(
        UUID studioProfileId,
        UUID roomId,
        String zoneId,
        LocalDate todayLocalDate,
        LocalTime currentLocalTime,
        LocalDateTime latestBookableLocalDateTime,
        int openingHour,
        int closingHour,
        LocalDate from,
        LocalDate to,
        List<StudioUnavailableIntervalResponse> unavailable
) {
}
