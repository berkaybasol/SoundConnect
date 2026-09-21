package com.berkayb.soundconnect.modules.event.plan;
import com.berkayb.soundconnect.modules.event.dto.response.EventResponseDto;
import java.time.LocalDate;
import java.util.UUID;
public record EventPlanOccurrenceResponse(LocalDate scheduledDate, UUID eventId, LocalDate eventDate,
        EventPlanOccurrenceStatus status, EventResponseDto event, EventPlanTemplate template) { }
