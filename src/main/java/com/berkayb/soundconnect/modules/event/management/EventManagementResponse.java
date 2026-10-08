package com.berkayb.soundconnect.modules.event.management;

import com.berkayb.soundconnect.modules.event.dto.response.EventResponseDto;
import java.time.Instant;
import java.util.List;

/** Opening management never materializes historical event cards. */
public record EventManagementResponse(List<EventResponseDto> upcomingEvents, long pastCount, Instant historyAsOf) {}
