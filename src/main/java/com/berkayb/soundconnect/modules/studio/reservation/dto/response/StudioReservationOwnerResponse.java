package com.berkayb.soundconnect.modules.studio.reservation.dto.response;

import com.berkayb.soundconnect.modules.studio.reservation.enums.StudioReservationStatus;

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
        long version
) {
}
