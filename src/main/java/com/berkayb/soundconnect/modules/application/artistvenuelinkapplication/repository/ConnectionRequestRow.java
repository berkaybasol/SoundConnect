package com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.repository;

import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.enums.RequestByType;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.enums.RequestStatus;
import java.time.LocalDateTime;
import java.util.UUID;

/** Scalar projection avoids loading artist/user collections for private management pages. */
public record ConnectionRequestRow(
        UUID id, UUID musicianProfileId, UUID bandId, UUID venueId,
        String musicianStageName, String bandName, String venueName, String message,
        RequestStatus status, RequestByType requestByType, LocalDateTime createdAt,
        UUID musicianMediaId, UUID bandMediaId, UUID venueMediaId, String musicianUsername
) {}
