package com.berkayb.soundconnect.modules.studio.reservation.entity;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StudioRoomReservationTest {

    @Test
    void acceptsExactWholeHourDurationsWithinTheCustomerLimit() {
        StudioRoomReservation reservation = reservation(
                "2026-08-04T06:00:00Z",
                "2026-08-04T10:00:00Z"
        );

        assertThatCode(reservation::validateDurationInvariant).doesNotThrowAnyException();
    }

    @Test
    void rejectsFractionalSecondDurations() {
        StudioRoomReservation reservation = reservation(
                "2026-08-04T06:00:00Z",
                "2026-08-04T07:00:00.500Z"
        );

        assertThatThrownBy(reservation::validateDurationInvariant)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exact whole hour");
    }

    private StudioRoomReservation reservation(String startsAt, String endsAt) {
        return StudioRoomReservation.builder()
                .startsAt(Instant.parse(startsAt))
                .endsAt(Instant.parse(endsAt))
                .build();
    }
}
