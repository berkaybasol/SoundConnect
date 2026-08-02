package com.berkayb.soundconnect.modules.studio.reservation.dto.response;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

public record StudioUnavailableIntervalResponse(
		Instant startsAt,
		Instant endsAt,
		LocalDate localDate,
		LocalTime localStartTime,
		LocalTime localEndTime
) {
}
