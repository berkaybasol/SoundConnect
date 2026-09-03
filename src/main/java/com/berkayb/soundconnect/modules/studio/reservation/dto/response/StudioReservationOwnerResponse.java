package com.berkayb.soundconnect.modules.studio.reservation.dto.response;

import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import com.berkayb.soundconnect.modules.studio.reservation.enums.StudioReservationStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * Owner-only guest summary. The client should open the existing public-profile
 * resolver with {@code requesterId}; schedule reads deliberately do not hydrate
 * another profile aggregate for every reservation.
 */
public record StudioReservationOwnerResponse(
        UUID id,
        UUID clientRequestId,
        UUID roomId,
        UUID requesterId,
        String requesterPublicCode,
        String requesterPhone,
        String requesterUsername,
        String requesterAvatarUrl,
        Instant startsAt,
        Instant endsAt,
        String zoneId,
		LocalDate localDate,
		LocalTime localStartTime,
		LocalTime localEndTime,
        StudioReservationStatus status,
        boolean completed,
        boolean approvalRequired,
        Long hourlyPriceMinor,
        Long totalPriceMinor,
        String currency,
        long version,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        ListenerVisibilityMode requesterVisibilityMode
) {
    public StudioReservationOwnerResponse {
        requesterVisibilityMode = requesterVisibilityMode == ListenerVisibilityMode.GHOST
                ? ListenerVisibilityMode.GHOST
                : null;
    }

    /** Keeps source compatibility for callers compiled against the original response shape. */
    public StudioReservationOwnerResponse(
            UUID id,
            UUID clientRequestId,
            UUID roomId,
            UUID requesterId,
            String requesterPublicCode,
            String requesterPhone,
            String requesterUsername,
            String requesterAvatarUrl,
            Instant startsAt,
            Instant endsAt,
            String zoneId,
            LocalDate localDate,
            LocalTime localStartTime,
            LocalTime localEndTime,
            StudioReservationStatus status,
            boolean completed,
            boolean approvalRequired,
            Long hourlyPriceMinor,
            Long totalPriceMinor,
            String currency,
            long version
    ) {
        this(
                id,
                clientRequestId,
                roomId,
                requesterId,
                requesterPublicCode,
                requesterPhone,
                requesterUsername,
                requesterAvatarUrl,
                startsAt,
                endsAt,
                zoneId,
                localDate,
                localStartTime,
                localEndTime,
                status,
                completed,
                approvalRequired,
                hourlyPriceMinor,
                totalPriceMinor,
                currency,
                version,
                null
        );
    }
}
