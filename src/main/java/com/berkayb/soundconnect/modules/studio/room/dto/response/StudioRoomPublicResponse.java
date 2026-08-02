package com.berkayb.soundconnect.modules.studio.room.dto.response;

import com.berkayb.soundconnect.modules.studio.room.enums.StudioRoomAvailabilityStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record StudioRoomPublicResponse(
        UUID id,
        UUID studioProfileId,
        int slotIndex,
        String name,
        String shortDescription,
        int capacity,
        int minimumCapacity,
        Long hourlyPriceMinor,
        String currency,
        boolean reservationApprovalRequired,
        List<String> features,
        List<StudioRoomPublicPhotoResponse> photos,
        LocalDate todayLocalDate,
        int todayAvailableHours,
        StudioRoomAvailabilityStatus todayAvailabilityStatus
) {
    public StudioRoomPublicResponse(
            UUID id,
            UUID studioProfileId,
            int slotIndex,
            String name,
            String shortDescription,
            int capacity,
            Long hourlyPriceMinor,
            String currency,
            boolean reservationApprovalRequired,
            List<String> features,
            List<StudioRoomPublicPhotoResponse> photos,
            LocalDate todayLocalDate,
            int todayAvailableHours,
            StudioRoomAvailabilityStatus todayAvailabilityStatus
    ) {
        this(
                id,
                studioProfileId,
                slotIndex,
                name,
                shortDescription,
                capacity,
                capacity,
                hourlyPriceMinor,
                currency,
                reservationApprovalRequired,
                features,
                photos,
                todayLocalDate,
                todayAvailableHours,
                todayAvailabilityStatus
        );
    }
}
