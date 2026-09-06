package com.berkayb.soundconnect.modules.profile.MusicianProfile.calendar.dto;

import com.berkayb.soundconnect.modules.event.dto.response.EventResponseDto;

import java.util.List;
import java.util.UUID;
import java.time.LocalDate;

public record MusicianCalendarResponse(
		UUID profileId,
		LocalDate startDate,
		LocalDate endDate,
		boolean visible,
		List<EventResponseDto> events,
		int page,
		int size,
		boolean hasNext
) {}
