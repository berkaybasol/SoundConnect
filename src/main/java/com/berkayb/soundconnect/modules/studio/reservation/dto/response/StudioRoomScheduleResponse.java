package com.berkayb.soundconnect.modules.studio.reservation.dto.response;

import com.berkayb.soundconnect.modules.studio.room.dto.response.StudioPageResponse;
import com.berkayb.soundconnect.modules.studio.room.dto.response.StudioRoomOwnerResponse;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

public record StudioRoomScheduleResponse(
        StudioRoomOwnerResponse room,
        String zoneId,
        LocalDate todayLocalDate,
        LocalTime currentLocalTime,
        LocalDateTime latestBookableLocalDateTime,
        LocalDate from,
        LocalDate to,
        StudioPageResponse<StudioReservationOwnerResponse> reservations,
        List<StudioOccupancyOwnerResponse> occupancies
) {
}
