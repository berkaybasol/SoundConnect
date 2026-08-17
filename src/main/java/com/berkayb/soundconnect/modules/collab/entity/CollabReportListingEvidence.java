package com.berkayb.soundconnect.modules.collab.entity;

import com.berkayb.soundconnect.modules.collab.enums.CollabBranch;
import com.berkayb.soundconnect.modules.collab.enums.CollabCadence;
import com.berkayb.soundconnect.modules.collab.enums.CollabListingStatus;
import com.berkayb.soundconnect.modules.collab.enums.CollabWantedType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CollabReportListingEvidence(
        String title,
        String description,
        UUID publisherActorId,
        String publisherDisplayName,
        CollabCadence cadence,
        CollabWantedType wantedType,
        UUID instrumentId,
        String instrumentName,
        CollabBranch branch,
        String customSpecialty,
        UUID cityId,
        String cityName,
        List<String> genres,
        Instant scheduledAt,
        Long feeAmountMinor,
        String currency,
        CollabListingStatus listingStatus
) {
    public CollabReportListingEvidence {
        genres = genres == null ? List.of() : List.copyOf(genres);
    }

    public static CollabReportListingEvidence capture(Collab listing) {
        return new CollabReportListingEvidence(
                listing.getTitle(),
                listing.getDescription(),
                listing.getPublisherActor().getId(),
                listing.getPublisherActor().getDisplayName(),
                listing.getCadence(),
                listing.getWantedType(),
                listing.getInstrument() == null ? null : listing.getInstrument().getId(),
                listing.getInstrument() == null ? null : listing.getInstrument().getName(),
                listing.getBranch(),
                listing.getCustomSpecialty(),
                listing.getCity().getId(),
                listing.getCity().getName(),
                listing.getGenres(),
                listing.getScheduledAt(),
                listing.getFeeAmountMinor(),
                listing.getCurrency(),
                listing.getStatus());
    }
}
