package com.berkayb.soundconnect.modules.application.venueapplication.dto.response;

import com.berkayb.soundconnect.modules.application.venueapplication.enums.ApplicationStatus;
import java.time.Instant;
import java.util.UUID;

/** Owned application result. A rejection explanation is disclosed only to its applicant. */
public record VenueApplicationDecisionDto(UUID id, UUID applicantUserId, ApplicationStatus status,
        String venueName, Instant applicationDate, Instant decisionDate, UUID venueId, String rejectionReason) { }
