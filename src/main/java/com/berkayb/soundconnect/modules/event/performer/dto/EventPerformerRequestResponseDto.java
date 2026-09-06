package com.berkayb.soundconnect.modules.event.performer.dto;

import com.berkayb.soundconnect.modules.event.enums.PerformerType;
import com.berkayb.soundconnect.modules.event.performer.enums.EventPerformerRequestStatus;
import com.berkayb.soundconnect.modules.event.performer.enums.EventPerformerRequestPurpose;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Instant;
import java.util.UUID;

public record EventPerformerRequestResponseDto(
		UUID id,
		UUID eventId,
		UUID musicianProfileId,
		UUID bandId,
		PerformerType performerType,
		String performerName,
		UUID venueId,
		String venueName,
		String venueProfilePictureUrl,
		String eventTitle,
		LocalDate eventDate,
		LocalTime startTime,
		LocalTime endTime,
		EventPerformerRequestStatus status,
		EventPerformerRequestPurpose requestPurpose,
		LocalDateTime createdAt,
		LocalDateTime decidedAt,
		String posterImage,
		boolean profileCalendarApproved,
		boolean decisionAllowed,
		boolean canReconsider,
		boolean expired,
		Instant serverNow,
		Instant eventStartsAt
) {
}
