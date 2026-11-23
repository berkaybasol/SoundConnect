package com.berkayb.soundconnect.modules.collab.dto.response;

import com.berkayb.soundconnect.modules.collab.enums.CollabBranch;
import com.berkayb.soundconnect.modules.collab.enums.CollabCadence;
import com.berkayb.soundconnect.modules.collab.enums.CollabListingStatus;
import com.berkayb.soundconnect.modules.collab.enums.CollabReportDecision;
import com.berkayb.soundconnect.modules.collab.enums.CollabReportReason;
import com.berkayb.soundconnect.modules.collab.enums.CollabReportStatus;
import com.berkayb.soundconnect.modules.collab.enums.CollabWantedType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** hasListingEvidence certifies a report-time snapshot. Legacy content has unverified capture time. */
public record CollabReportAdminResponse(
        UUID id,
        long version,
        CollabReportStatus status,
        CollabReportReason reason,
        String details,
        Instant reportedAt,
        UUID listingId,
        boolean hasListingEvidence,
        String listingTitle,
        String listingDescription,
        CollabListingStatus listingStatus,
        CollabListingStatus listingStatusAtReport,
        UUID publisherActorId,
        String publisherDisplayName,
        CollabCadence cadence,
        CollabWantedType wantedType,
        CollabInstrumentSummary instrument,
        CollabBranch branch,
        String customSpecialty,
        CollabCitySummary city,
        List<String> listingGenres,
        Instant scheduledAt,
        Long feeAmountMinor,
        String currency,
        UUID reporterUserId,
        String reporterUsername,
        CollabReportDecision reviewDecision,
        UUID reviewedByUserId,
        String reviewedByUsername,
        Instant reviewedAt,
        String resolutionNote
) {
    public CollabReportAdminResponse {
        listingGenres = List.copyOf(listingGenres);
    }
}
