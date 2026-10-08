package com.berkayb.soundconnect.modules.event.plan;

import com.berkayb.soundconnect.modules.event.support.EventScheduleClock;
import com.berkayb.soundconnect.shared.exception.*;
import java.time.*;
import java.util.*;

/** Bounded, host-timezone independent calendar arithmetic shared by preview and materialization. */
public final class EventPlanRules {
    public static final int HORIZON_DAYS = 28;
    public static final int MAX_EXCLUDED_DATES = 366;
    private EventPlanRules() { }

    public static EventPlanDefinition normalize(EventPlanDefinition value) {
        if (value == null || value.venueId() == null || !validDate(value.startDate())
                || value.untilDate() != null && (!validDate(value.untilDate()) || value.untilDate().isBefore(value.startDate()))
                || value.weekdays() == null || value.weekdays().isEmpty() || value.weekdays().size() > 7) throw invalid();
        var days = new TreeSet<Integer>();
        for (Integer day : value.weekdays()) if (day == null || day < 1 || day > 7 || !days.add(day)) throw invalid();
        List<LocalDate> excluded = value.excludedDates() == null ? List.of() : value.excludedDates();
        if (excluded.size() > MAX_EXCLUDED_DATES) throw invalid();
        var dates = new TreeSet<LocalDate>();
        for (LocalDate date : excluded) {
            if (!validDate(date) || date.isBefore(value.startDate()) || value.untilDate() != null && date.isAfter(value.untilDate())
                    || !days.contains(date.getDayOfWeek().getValue()) || !dates.add(date)) throw invalid();
        }
        return new EventPlanDefinition(value.venueId(), value.startDate(), value.untilDate(), List.copyOf(days),
                List.copyOf(dates), normalizeTemplate(value.template()));
    }

    public static EventPlanTemplate normalizeTemplate(EventPlanTemplate t) {
        if (t == null || t.title() == null || t.title().isBlank() || t.title().length() > 255
                || t.description() != null && t.description().length() > 500 || t.startTime() == null
                || t.startTime().getNano() != 0 || t.endTime() != null && t.endTime().getNano() != 0
                || t.endTime() != null && !t.endTime().isAfter(t.startTime())
                || t.posterImage() != null && t.posterImage().length() > 255
                || t.manualPerformerName() != null && t.manualPerformerName().length() > 120) throw invalid();
        String manual = nullable(t.manualPerformerName());
        if ((t.musicianProfileId() == null ? 0 : 1) + (t.bandId() == null ? 0 : 1) + (manual == null ? 0 : 1) > 1) throw invalid();
        return new EventPlanTemplate(t.title().trim(), nullable(t.description()), t.startTime(), t.endTime(),
                normalizePoster(t.posterImage()), t.musicianProfileId(), t.bandId(), manual);
    }

    public static boolean validDate(LocalDate date) { return date != null && date.getYear() >= 1 && date.getYear() <= 9999; }
    public static boolean matches(EventPlanDefinition d, LocalDate date) {
        return !date.isBefore(d.startDate()) && (d.untilDate() == null || !date.isAfter(d.untilDate()))
                && d.weekdays().contains(date.getDayOfWeek().getValue()) && !d.excludedDates().contains(date);
    }
    public static Instant startsAt(LocalDate date, LocalTime time) { return date.atTime(time).atZone(EventScheduleClock.ZONE).toInstant(); }
    public static List<LocalDate> dates(EventPlanDefinition d, Instant now) {
        LocalDate today = now.atZone(EventScheduleClock.ZONE).toLocalDate();
        List<LocalDate> result = new ArrayList<>();
        for (int i = 0; i < HORIZON_DAYS; i++) {
            LocalDate date = today.plusDays(i);
            if (matches(d, date) && startsAt(date, d.template().startTime()).isAfter(now)) result.add(date);
        }
        return List.copyOf(result);
    }
    public static EventPlanPreview preview(EventPlanDefinition d, Instant now) {
        LocalDate first = now.atZone(EventScheduleClock.ZONE).toLocalDate();
        if (first.isBefore(d.startDate())) first = d.startDate();
        LocalDate maximum = LocalDate.of(9999,12,31);
        LocalDate through = first.plusDays(HORIZON_DAYS - 1);
        if(through.isAfter(maximum))through=maximum;
        List<LocalDate> dates = new ArrayList<>();
        for (int i=0;i<HORIZON_DAYS;i++) {
            LocalDate date=first.plusDays(i);
            if(date.isAfter(maximum))break;
            if(matches(d,date)&&startsAt(date,d.template().startTime()).isAfter(now))dates.add(date);
        }
        boolean more=hasFuture(d,through.plusDays(1).atStartOfDay(EventScheduleClock.ZONE).toInstant().minusNanos(1));
        return new EventPlanPreview(List.copyOf(dates),through,more,now);
    }
    public static List<LocalDate> previewDates(EventPlanDefinition d, Instant now) { return preview(d,now).dates(); }
    public static boolean hasFuture(EventPlanDefinition d, Instant now) {
        LocalDate from = now.atZone(EventScheduleClock.ZONE).toLocalDate();
        if (from.isBefore(d.startDate())) from = d.startDate();
        // At most 366 exclusions plus one week; no iteration across an unbounded series.
        for (int i = 0; i <= (MAX_EXCLUDED_DATES + 1) * 7; i++) {
            if (from.getYear() > 9999 || d.untilDate() != null && from.isAfter(d.untilDate())) return false;
            if (matches(d, from) && startsAt(from, d.template().startTime()).isAfter(now)) return true;
            from = from.plusDays(1);
        }
        return false;
    }
    public static boolean consentScopeChanged(EventPlanDefinition a, EventPlanDefinition b) {
        return !Objects.equals(a.startDate(), b.startDate()) || !Objects.equals(a.untilDate(), b.untilDate())
                || !a.weekdays().equals(b.weekdays()) || !a.excludedDates().equals(b.excludedDates())
                || performerOrScheduleChanged(a.template(), b.template());
    }
    public static boolean performerOrScheduleChanged(EventPlanTemplate a, EventPlanTemplate b) {
        return !Objects.equals(a.startTime(), b.startTime()) || !Objects.equals(a.endTime(), b.endTime())
                || !Objects.equals(a.musicianProfileId(), b.musicianProfileId()) || !Objects.equals(a.bandId(), b.bandId())
                || !Objects.equals(a.manualPerformerName(), b.manualPerformerName());
    }
    private static String nullable(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static String normalizePoster(String value) {
        String reference = nullable(value);
        if (reference == null) return null;
        try {
            // Match ordinary event creation and the media reference guard's canonical UUID comparison.
            return UUID.fromString(reference).toString();
        } catch (IllegalArgumentException legacyReference) {
            return reference;
        }
    }
    private static SoundConnectException invalid() { return new SoundConnectException(ErrorType.INVALID_PARAMETER); }
}
