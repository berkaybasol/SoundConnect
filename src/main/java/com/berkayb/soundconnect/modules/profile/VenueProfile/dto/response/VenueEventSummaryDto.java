package com.berkayb.soundconnect.modules.profile.VenueProfile.dto.response;

import com.berkayb.soundconnect.modules.event.enums.PerformerType;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

public record VenueEventSummaryDto(
		UUID eventId,
		String title,
		String posterImage,
		String performerName,
		UUID musicianProfileId,
		UUID bandId,
		PerformerType performerType,
		LocalDate eventDate,
		LocalTime startTime,
		LocalTime endTime
) {
}
