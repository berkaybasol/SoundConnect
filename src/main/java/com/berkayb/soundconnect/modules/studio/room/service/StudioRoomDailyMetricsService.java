package com.berkayb.soundconnect.modules.studio.room.service;

import com.berkayb.soundconnect.modules.profile.StudioProfile.entity.StudioProfile;
import com.berkayb.soundconnect.modules.studio.reservation.enums.StudioReservationStatus;
import com.berkayb.soundconnect.modules.studio.reservation.repository.StudioRoomOccupancyRepository;
import com.berkayb.soundconnect.modules.studio.reservation.repository.StudioRoomReservationRepository;
import com.berkayb.soundconnect.modules.studio.reservation.support.StudioDateRange;
import com.berkayb.soundconnect.modules.studio.reservation.support.StudioReservationTimeProvider;
import com.berkayb.soundconnect.modules.studio.room.enums.StudioRoomAvailabilityStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class StudioRoomDailyMetricsService {
    private static final int OPERATING_HOURS =
            StudioReservationTimeProvider.CLOSING_HOUR - StudioReservationTimeProvider.OPENING_HOUR;
    private static final List<StudioReservationStatus> ACTIVE_RESERVATION_STATUSES = List.of(
            StudioReservationStatus.PENDING_APPROVAL,
            StudioReservationStatus.CONFIRMED
    );

    private final StudioRoomReservationRepository reservationRepository;
    private final StudioRoomOccupancyRepository occupancyRepository;
    private final StudioReservationTimeProvider timeProvider;

    @Transactional(readOnly = true)
    public Map<UUID, StudioRoomDailyMetrics> load(
            StudioProfile profile,
            Collection<UUID> roomIds
    ) {
        if (roomIds == null || roomIds.isEmpty()) {
            return Map.of();
        }
        List<UUID> distinctRoomIds = roomIds.stream().distinct().toList();
        StudioDateRange window = timeProvider.currentOperatingRange(profile);
        LocalDate localDate = window.from();

        Map<UUID, Long> reservationCounts = new HashMap<>();
        reservationRepository.countDailyByRooms(
                distinctRoomIds,
                ACTIVE_RESERVATION_STATUSES,
				StudioReservationStatus.PENDING_APPROVAL,
                window.startsAt(),
				window.endsAt(),
				timeProvider.now()
        ).forEach(row -> reservationCounts.put(row.getRoomId(), row.getReservationCount()));

        Map<UUID, Integer> occupiedHours = new HashMap<>();
        occupancyRepository.sumDailyOccupiedHoursByRooms(
                distinctRoomIds,
                window.startsAt(),
                window.endsAt()
        ).forEach(row -> occupiedHours.put(row.getRoomId(), clampOccupiedHours(row.getOccupiedHours())));

        Map<UUID, StudioRoomDailyMetrics> result = new HashMap<>();
        for (UUID roomId : distinctRoomIds) {
            int occupied = occupiedHours.getOrDefault(roomId, 0);
            int available = OPERATING_HOURS - occupied;
            result.put(roomId, new StudioRoomDailyMetrics(
                    localDate,
                    reservationCounts.getOrDefault(roomId, 0L),
                    occupied,
                    available,
                    availabilityStatus(available)
            ));
        }
        return Map.copyOf(result);
    }

    public StudioRoomDailyMetrics empty(StudioProfile profile) {
        return new StudioRoomDailyMetrics(
                timeProvider.currentLocalDate(profile),
                0,
                0,
                OPERATING_HOURS,
                StudioRoomAvailabilityStatus.AVAILABLE
        );
    }

    private int clampOccupiedHours(int occupiedHours) {
        return Math.min(Math.max(occupiedHours, 0), OPERATING_HOURS);
    }

    private StudioRoomAvailabilityStatus availabilityStatus(int availableHours) {
        if (availableHours <= 0) {
            return StudioRoomAvailabilityStatus.FULLY_BOOKED;
        }
        if (availableHours == OPERATING_HOURS) {
            return StudioRoomAvailabilityStatus.AVAILABLE;
        }
        return StudioRoomAvailabilityStatus.PARTIALLY_AVAILABLE;
    }
}
