package com.berkayb.soundconnect.modules.event.publication;

import com.berkayb.soundconnect.modules.event.enums.PerformerType;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

public record EventProfilePublicationDto(UUID eventId, PerformerType targetType, UUID targetId,
        boolean visible, long version, String eventTitle, LocalDate eventDate, LocalTime startTime,
        LocalTime endTime, String posterImage, UUID venueId, String venueName, String performerName) { }
