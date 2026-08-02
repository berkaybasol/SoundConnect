package com.berkayb.soundconnect.modules.studio.reservation.support;

import com.berkayb.soundconnect.modules.profile.StudioProfile.entity.StudioProfile;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Component
public class StudioReservationTimeProvider {
    public static final int OPENING_HOUR = 9;
    public static final int CLOSING_HOUR = 23;
    public static final int MAX_DURATION_HOURS = 4;
    public static final int MAX_MANUAL_BLOCK_DURATION_HOURS =
            CLOSING_HOUR - OPENING_HOUR;
    public static final int MAX_ADVANCE_DAYS = 365;
    public static final int MAX_QUERY_DAYS = 31;
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Europe/Istanbul");

    private final Clock clock;

    public StudioReservationTimeProvider() {
        this(Clock.systemUTC());
    }

    StudioReservationTimeProvider(Clock clock) {
        this.clock = clock;
    }

    public Instant now() {
        return clock.instant();
    }

    public ZoneId zoneOf(StudioProfile profile) {
        String configured = profile.getTimeZone();
        if (configured == null || configured.isBlank()) {
            return DEFAULT_ZONE;
        }
        try {
            return ZoneId.of(configured);
        } catch (DateTimeException ignored) {
            return DEFAULT_ZONE;
        }
    }

    public StudioBookingWindow validateBookingWindow(
            StudioProfile profile,
            LocalDate date,
            LocalTime startTime,
            int durationHours
    ) {
        return validateFutureWindow(
                profile,
                date,
                startTime,
                durationHours,
                MAX_DURATION_HOURS
        );
    }

    public StudioBookingWindow validateManualBlockWindow(
            StudioProfile profile,
            LocalDate date,
            LocalTime startTime,
            int durationHours
    ) {
        return validateFutureWindow(
                profile,
                date,
                startTime,
                durationHours,
                MAX_MANUAL_BLOCK_DURATION_HOURS
        );
    }

    private StudioBookingWindow validateFutureWindow(
            StudioProfile profile,
            LocalDate date,
            LocalTime startTime,
            int durationHours,
            int maximumDurationHours
    ) {
        if (date == null || startTime == null
                || durationHours < 1 || durationHours > maximumDurationHours
                || startTime.getMinute() != 0 || startTime.getSecond() != 0 || startTime.getNano() != 0
                || startTime.getHour() < OPENING_HOUR
                || startTime.getHour() + durationHours > CLOSING_HOUR) {
            throw invalidWindow();
        }

        ZoneId zone = zoneOf(profile);
        LocalDate today = LocalDate.now(clock.withZone(zone));
        if (date.isBefore(today) || date.isAfter(today.plusDays(MAX_ADVANCE_DAYS))) {
            throw invalidWindow();
        }

        Instant startsAt = resolve(date.atTime(startTime), zone);
        Instant endsAt = resolve(date.atTime(startTime).plusHours(durationHours), zone);
        Instant now = now();
        if (!startsAt.isAfter(now)
                || startsAt.isAfter(now.plus(MAX_ADVANCE_DAYS, ChronoUnit.DAYS))
                || !endsAt.isAfter(startsAt)
                || !Duration.between(startsAt, endsAt).equals(Duration.ofHours(durationHours))) {
            throw invalidWindow();
        }
        return new StudioBookingWindow(startsAt, endsAt);
    }

    public StudioBookingWindow convertWithoutFutureValidation(
            StudioProfile profile,
            LocalDate date,
            LocalTime startTime,
            int durationHours
    ) {
        return convertWithoutFutureValidation(
                profile,
                date,
                startTime,
                durationHours,
                MAX_DURATION_HOURS
        );
    }

    public StudioBookingWindow convertManualBlockWithoutFutureValidation(
            StudioProfile profile,
            LocalDate date,
            LocalTime startTime,
            int durationHours
    ) {
        return convertWithoutFutureValidation(
                profile,
                date,
                startTime,
                durationHours,
                MAX_MANUAL_BLOCK_DURATION_HOURS
        );
    }

    private StudioBookingWindow convertWithoutFutureValidation(
            StudioProfile profile,
            LocalDate date,
            LocalTime startTime,
            int durationHours,
            int maximumDurationHours
    ) {
        if (date == null || startTime == null
                || durationHours < 1 || durationHours > maximumDurationHours) {
            throw invalidWindow();
        }
        ZoneId zone = zoneOf(profile);
        Instant startsAt = resolve(date.atTime(startTime), zone);
        Instant endsAt = resolve(date.atTime(startTime).plusHours(durationHours), zone);
        if (!Duration.between(startsAt, endsAt).equals(Duration.ofHours(durationHours))) {
            throw invalidWindow();
        }
        return new StudioBookingWindow(startsAt, endsAt);
    }

    public StudioDateRange validateOwnerRange(StudioProfile profile, LocalDate from, LocalDate to) {
        return validateRange(profile, from, to);
    }

    public StudioDateRange validatePublicRange(StudioProfile profile, LocalDate from, LocalDate to) {
        StudioDateRange range = validateRange(profile, from, to);
        LocalDate today = LocalDate.now(clock.withZone(zoneOf(profile)));
        if (from.isBefore(today) || to.isAfter(today.plusDays(MAX_ADVANCE_DAYS))) {
            throw invalidWindow();
        }
        return range;
    }

    public StudioDateRange currentOperatingRange(StudioProfile profile) {
        ZoneId zone = zoneOf(profile);
        LocalDate today = LocalDate.now(clock.withZone(zone));
        return new StudioDateRange(
                today,
                today,
                resolve(today.atTime(OPENING_HOUR, 0), zone),
                resolve(today.atTime(CLOSING_HOUR, 0), zone)
        );
    }

    public LocalDate currentLocalDate(StudioProfile profile) {
        return LocalDate.now(clock.withZone(zoneOf(profile)));
    }

    public StudioBookingClock bookingClock(StudioProfile profile) {
        ZoneId zone = zoneOf(profile);
        Instant snapshot = now();
        ZonedDateTime localNow = snapshot.atZone(zone);
        return new StudioBookingClock(
                localNow.toLocalDate(),
                localNow.toLocalTime(),
                snapshot.plus(MAX_ADVANCE_DAYS, ChronoUnit.DAYS)
                        .atZone(zone)
                        .toLocalDateTime()
        );
    }

    private StudioDateRange validateRange(
            StudioProfile profile,
            LocalDate from,
            LocalDate to
    ) {
        if (from == null || to == null || to.isBefore(from)
                || ChronoUnit.DAYS.between(from, to) >= MAX_QUERY_DAYS) {
            throw invalidWindow();
        }
        ZoneId zone = zoneOf(profile);
        Instant startsAt = resolve(from.atStartOfDay(), zone);
        Instant endsAt = resolve(to.plusDays(1).atStartOfDay(), zone);
        return new StudioDateRange(from, to, startsAt, endsAt);
    }

    private Instant resolve(LocalDateTime localDateTime, ZoneId zone) {
        List<ZoneOffset> validOffsets = zone.getRules().getValidOffsets(localDateTime);
        if (validOffsets.size() != 1) {
            throw invalidWindow();
        }
        return localDateTime.toInstant(validOffsets.getFirst());
    }

    private SoundConnectException invalidWindow() {
        return new SoundConnectException(ErrorType.STUDIO_RESERVATION_WINDOW_INVALID);
    }
}
