package com.berkayb.soundconnect.modules.collab.dto.response;

import com.berkayb.soundconnect.modules.collab.enums.CollabReportReason;

import java.time.Instant;
import java.util.UUID;

public record CollabReportResponse(UUID id, UUID listingId, CollabReportReason reason, String details, Instant reportedAt) {}
