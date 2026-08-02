package com.berkayb.soundconnect.modules.studio.reservation.support;

import com.berkayb.soundconnect.modules.profile.StudioProfile.entity.StudioProfile;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StudioReservationTimeProviderTest {
    private final StudioReservationTimeProvider provider = new StudioReservationTimeProvider(
            Clock.fixed(Instant.parse("2026-07-21T08:00:00Z"), ZoneOffset.UTC)
    );
    private final StudioProfile profile = StudioProfile.builder()
            .timeZone("Europe/Istanbul")
            .build();

    @Test
    void convertsStudioLocalWholeHoursToUtc() {
        StudioBookingWindow result = provider.validateBookingWindow(
                profile,
                LocalDate.of(2026, 7, 22),
                LocalTime.of(9, 0),
                4
        );

        assertThat(result.startsAt()).isEqualTo(Instant.parse("2026-07-22T06:00:00Z"));
        assertThat(result.endsAt()).isEqualTo(Instant.parse("2026-07-22T10:00:00Z"));
    }

    @Test
    void allowsOwnerToBlockTheRemainingOperatingDayWithoutRelaxingCustomerLimit() {
        StudioBookingWindow result = provider.validateManualBlockWindow(
                profile,
                LocalDate.of(2026, 7, 22),
                LocalTime.of(15, 0),
                8
        );

        assertThat(result.startsAt()).isEqualTo(Instant.parse("2026-07-22T12:00:00Z"));
        assertThat(result.endsAt()).isEqualTo(Instant.parse("2026-07-22T20:00:00Z"));
        assertInvalid(() -> provider.validateBookingWindow(
                profile,
                LocalDate.of(2026, 7, 22),
                LocalTime.of(15, 0),
                8
        ));
    }

    @Test
    void rejectsFractionalHoursAndClosingTimeOverflow() {
        assertInvalid(() -> provider.validateBookingWindow(
                profile,
                LocalDate.of(2026, 7, 22),
                LocalTime.of(10, 30),
                1
        ));
        assertInvalid(() -> provider.validateBookingWindow(
                profile,
                LocalDate.of(2026, 7, 22),
                LocalTime.of(22, 0),
                2
        ));
    }

    @Test
    void rejectsBookingsMoreThanOneYearAhead() {
        assertInvalid(() -> provider.validateBookingWindow(
                profile,
                LocalDate.of(2027, 7, 22),
                LocalTime.of(10, 0),
                1
        ));
    }

    @Test
    void exposesOneAuthoritativeStudioLocalBookingClockSnapshot() {
        StudioBookingClock result = provider.bookingClock(profile);

        assertThat(result.todayLocalDate()).isEqualTo(LocalDate.of(2026, 7, 21));
        assertThat(result.currentLocalTime()).isEqualTo(LocalTime.of(11, 0));
        assertThat(result.latestBookableLocalDateTime())
                .isEqualTo(LocalDateTime.of(2027, 7, 21, 11, 0));
    }

    private void assertInvalid(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(SoundConnectException.class)
                .extracting(exception -> ((SoundConnectException) exception).getErrorType())
                .isEqualTo(ErrorType.STUDIO_RESERVATION_WINDOW_INVALID);
    }
}
