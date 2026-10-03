package com.berkayb.soundconnect.modules.application.venueapplication.dto.response;

import com.berkayb.soundconnect.modules.application.venueapplication.enums.ApplicationStatus;
import java.time.Instant;
import java.util.UUID;

/** Private contact details and moderator free text are intentionally absent. */
public record VenueApplicationDecisionDto(UUID id, UUID applicantUserId, ApplicationStatus status,
        String venueName, Instant applicationDate, Instant decisionDate, UUID venueId) { }
