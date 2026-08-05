package com.berkayb.soundconnect.modules.studio.reservation.entity;

import com.berkayb.soundconnect.modules.studio.reservation.enums.StudioOccupancyType;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StudioRoomOccupancyTest {

    @Test
    void allowsFullOperatingDayForManualBlocks() {
        StudioRoomOccupancy occupancy = occupancy(
                StudioOccupancyType.MANUAL_BLOCK,
                "2026-08-04T06:00:00Z",
                "2026-08-04T20:00:00Z"
        );

        assertThatCode(occupancy::validateDurationInvariant).doesNotThrowAnyException();
    }

    @Test
    void keepsCustomerReservationOccupancyAtFourHoursMaximum() {
        StudioRoomOccupancy occupancy = occupancy(
                StudioOccupancyType.RESERVATION,
                "2026-08-04T06:00:00Z",
                "2026-08-04T11:00:00Z"
        );

        assertThatThrownBy(occupancy::validateDurationInvariant)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RESERVATION");
    }

    @Test
    void rejectsSubSecondDurationsThatAreNotExactWholeHours() {
        StudioRoomOccupancy occupancy = StudioRoomOccupancy.builder()
                .type(StudioOccupancyType.MANUAL_BLOCK)
                .startsAt(Instant.parse("2026-08-04T06:00:00Z"))
                .endsAt(Instant.parse("2026-08-04T07:00:00.500Z"))
                .build();

        assertThatThrownBy(occupancy::validateDurationInvariant)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MANUAL_BLOCK");
    }

    private StudioRoomOccupancy occupancy(
            StudioOccupancyType type,
            String startsAt,
            String endsAt
    ) {
        return StudioRoomOccupancy.builder()
                .type(type)
                .startsAt(Instant.parse(startsAt))
                .endsAt(Instant.parse(endsAt))
                .build();
    }
}
