package com.berkayb.soundconnect.modules.studio.room.service;

import com.berkayb.soundconnect.modules.profile.StudioProfile.entity.StudioProfile;
import com.berkayb.soundconnect.modules.studio.reservation.repository.StudioRoomOccupancyRepository;
import com.berkayb.soundconnect.modules.studio.reservation.repository.StudioRoomReservationRepository;
import com.berkayb.soundconnect.modules.studio.reservation.repository.projection.StudioDailyOccupancyHoursProjection;
import com.berkayb.soundconnect.modules.studio.reservation.repository.projection.StudioDailyReservationCountProjection;
import com.berkayb.soundconnect.modules.studio.reservation.support.StudioDateRange;
import com.berkayb.soundconnect.modules.studio.reservation.support.StudioReservationTimeProvider;
import com.berkayb.soundconnect.modules.studio.room.enums.StudioRoomAvailabilityStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StudioRoomDailyMetricsServiceTest {
    @Mock StudioRoomReservationRepository reservationRepository;
    @Mock StudioRoomOccupancyRepository occupancyRepository;
    @Mock StudioReservationTimeProvider timeProvider;
    @InjectMocks StudioRoomDailyMetricsService service;

    @Test
    void returnsWithoutQueriesForAnEmptyRoomPage() {
        assertThat(service.load(StudioProfile.builder().build(), List.of())).isEmpty();
		verify(reservationRepository, never()).countDailyByRooms(any(), any(), any(), any(), any(), any());
        verify(occupancyRepository, never()).sumDailyOccupiedHoursByRooms(any(), any(), any());
    }

    @Test
    void aggregatesAllRoomCardsInTwoBatchQueriesAndDerivesAvailability() {
        UUID busyRoomId = UUID.randomUUID();
        UUID emptyRoomId = UUID.randomUUID();
        StudioProfile profile = StudioProfile.builder().timeZone("Europe/Istanbul").build();
        StudioDateRange window = new StudioDateRange(
                LocalDate.of(2026, 7, 21),
                LocalDate.of(2026, 7, 21),
                Instant.parse("2026-07-21T06:00:00Z"),
                Instant.parse("2026-07-21T20:00:00Z")
        );
        when(timeProvider.currentOperatingRange(profile)).thenReturn(window);
		when(timeProvider.now()).thenReturn(Instant.parse("2026-07-21T10:00:00Z"));

        StudioDailyReservationCountProjection reservationRow = mock(StudioDailyReservationCountProjection.class);
        when(reservationRow.getRoomId()).thenReturn(busyRoomId);
        when(reservationRow.getReservationCount()).thenReturn(2L);
		when(reservationRepository.countDailyByRooms(any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(reservationRow));

        StudioDailyOccupancyHoursProjection occupancyRow = mock(StudioDailyOccupancyHoursProjection.class);
        when(occupancyRow.getRoomId()).thenReturn(busyRoomId);
        when(occupancyRow.getOccupiedHours()).thenReturn(3);
        when(occupancyRepository.sumDailyOccupiedHoursByRooms(any(), any(), any()))
                .thenReturn(List.of(occupancyRow));

        var result = service.load(profile, List.of(busyRoomId, emptyRoomId));

        assertThat(result.get(busyRoomId).reservationCount()).isEqualTo(2);
        assertThat(result.get(busyRoomId).occupiedHours()).isEqualTo(3);
        assertThat(result.get(busyRoomId).availableHours()).isEqualTo(11);
        assertThat(result.get(busyRoomId).availabilityStatus())
                .isEqualTo(StudioRoomAvailabilityStatus.PARTIALLY_AVAILABLE);
        assertThat(result.get(emptyRoomId).availableHours()).isEqualTo(14);
        assertThat(result.get(emptyRoomId).availabilityStatus())
                .isEqualTo(StudioRoomAvailabilityStatus.AVAILABLE);
		verify(reservationRepository).countDailyByRooms(any(), any(), any(), any(), any(), any());
        verify(occupancyRepository).sumDailyOccupiedHoursByRooms(any(), any(), any());
    }
}
