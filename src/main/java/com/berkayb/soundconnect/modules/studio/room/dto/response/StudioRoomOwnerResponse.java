package com.berkayb.soundconnect.modules.studio.room.dto.response;

import com.berkayb.soundconnect.modules.studio.room.enums.StudioRoomAvailabilityStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record StudioRoomOwnerResponse(
        UUID id,
        UUID studioProfileId,
        UUID clientRequestId,
        int slotIndex,
        String name,
        String shortDescription,
        int capacity,
        int minimumCapacity,
        Long hourlyPriceMinor,
        String currency,
        boolean reservationApprovalRequired,
        Boolean pendingReservationApprovalRequired,
        Instant reservationApprovalPolicyEffectiveAt,
        List<String> features,
        List<StudioRoomPhotoResponse> photos,
        LocalDate todayLocalDate,
        long todayReservationCount,
        int todayOccupiedHours,
        int todayAvailableHours,
        StudioRoomAvailabilityStatus todayAvailabilityStatus,
        Instant archivedAt,
        long version
) {
    public StudioRoomOwnerResponse(
            UUID id,
            UUID studioProfileId,
            UUID clientRequestId,
            int slotIndex,
            String name,
            String shortDescription,
            int capacity,
            Long hourlyPriceMinor,
            String currency,
            boolean reservationApprovalRequired,
            List<String> features,
            List<StudioRoomPhotoResponse> photos,
            LocalDate todayLocalDate,
            long todayReservationCount,
            int todayOccupiedHours,
            int todayAvailableHours,
            StudioRoomAvailabilityStatus todayAvailabilityStatus,
            Instant archivedAt,
            long version
    ) {
        this(
                id,
                studioProfileId,
                clientRequestId,
                slotIndex,
                name,
                shortDescription,
                capacity,
                capacity,
                hourlyPriceMinor,
                currency,
                reservationApprovalRequired,
                null,
                null,
                features,
                photos,
                todayLocalDate,
                todayReservationCount,
                todayOccupiedHours,
                todayAvailableHours,
                todayAvailabilityStatus,
                archivedAt,
                version
        );
    }
}
