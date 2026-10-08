package com.berkayb.soundconnect.modules.notification.campaign;

import com.berkayb.soundconnect.shared.exception.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static com.berkayb.soundconnect.modules.notification.campaign.CampaignContract.*;

/** Bounded calendar arithmetic, following event-plan rules without coupling the two domains. */
public final class CampaignRules {
    public static final Set<String> PROFILES = Set.of("MUSICIAN", "LISTENER", "VENUE", "STUDIO");

    private CampaignRules() { }

    public static Write normalize(Write w) {
        if (w == null || w.audience() == null || w.target() == null || w.schedule() == null) {
            throw invalid();
        }
        var a = w.audience();
        var profiles = a.profileTypes() == null ? Set.<String>of() : a.profileTypes();
        var users = a.userIds() == null ? Set.<UUID>of() : a.userIds();
        if (a.mode() == null || profiles.size() > 4 || profiles.stream().anyMatch(p -> p == null || !PROFILES.contains(p))
                || users.size() > 100 || users.stream().anyMatch(Objects::isNull)
                || a.mode() == Mode.ALL && (!profiles.isEmpty() || !users.isEmpty())
                || a.mode() == Mode.PROFILE_TYPES && (profiles.isEmpty() || !users.isEmpty())
                || a.mode() == Mode.USERS && (users.isEmpty() || !profiles.isEmpty())) {
            throw invalid();
        }
        var t = w.target();
        if (t.kind() == null || Set.of(Kind.PROFILE, Kind.EVENT, Kind.CONTENT).contains(t.kind()) != (t.targetId() != null)) {
            throw invalid();
        }
        var s = w.schedule();
        try {
            var zone = ZoneId.of(s.zoneId());
            Instant start = s.startsAt();
            if (s.localStartsAt() != null) {
                Instant local = s.localStartsAt().atZone(zone).toInstant();
                if (start != null && !start.equals(local)) {
                    throw invalid();
                }
                start = local;
            }
            Instant end = s.endsAt();
            if (s.localEndsAt() != null) {
                Instant local = s.localEndsAt().atZone(zone).toInstant();
                if (end != null && !end.equals(local)) {
                    throw invalid();
                }
                end = local;
            }
            var days = s.weekDays() == null ? Set.<Integer>of() : s.weekDays();
            if (start == null || s.repeat() == null || start.atZone(zone).getYear() < 2000 || start.atZone(zone).getYear() > 2100
                    || end != null && (!end.isAfter(start) || end.isAfter(start.plus(3660, ChronoUnit.DAYS)))
                    || s.maxOccurrences() != null && (s.maxOccurrences() < 1 || s.maxOccurrences() > 10000)
                    || s.repeat() != Repeat.ONCE && end == null && s.maxOccurrences() == null
                    || s.repeat() == Repeat.INTERVAL && (s.intervalDays() == null || s.intervalDays() < 1 || s.intervalDays() > 365)
                    || s.repeat() != Repeat.INTERVAL && s.intervalDays() != null
                    || s.repeat() == Repeat.WEEKLY && (days.isEmpty() || days.size() > 7 || days.stream().anyMatch(d -> d == null || d < 1 || d > 7))
                    || s.repeat() != Repeat.WEEKLY && !days.isEmpty()) {
                throw invalid();
            }
            s = new Schedule(start, zone.getId(), s.repeat(), s.intervalDays(), Set.copyOf(days), end, s.maxOccurrences(),
                    s.localStartsAt() == null ? LocalDateTime.ofInstant(start, zone) : s.localStartsAt(),
                    end == null ? null : s.localEndsAt() == null ? LocalDateTime.ofInstant(end, zone) : s.localEndsAt());
        } catch (DateTimeException | NullPointerException bad) {
            throw invalid();
        }
        return new Write(w.requestId(), w.expectedVersion(), text(w.title(), 120), text(w.message(), 500),
                new Audience(a.mode(), Set.copyOf(profiles), Set.copyOf(users)), t, s);
    }

    public static String text(String value, int maximum) {
        if (value == null) {
            throw invalid();
        }
        String normalized = value.replaceAll("[\\r\\n\\t]+", " ");
        if (normalized.codePoints().anyMatch(c -> Character.isISOControl(c) || Character.getType(c) == Character.FORMAT
                || Character.getType(c) == Character.SURROGATE || c == 0x2028 || c == 0x2029)) {
            throw invalid();
        }
        int from = 0, to = normalized.length();
        while (from < to && whitespace(normalized.charAt(from))) {
            from++;
        }
        while (to > from && whitespace(normalized.charAt(to - 1))) {
            to--;
        }
        String result = normalized.substring(from, to);
        if (result.isEmpty() || result.length() > maximum) {
            throw invalid();
        }
        return result;
    }

    private static boolean whitespace(char value) {
        return Character.isWhitespace(value) || Character.isSpaceChar(value);
    }

    /** First calendar occurrence strictly after the argument. No iteration over elapsed years. */
    public static Instant next(Schedule s, Instant after) {
        if (s.repeat() == Repeat.ONCE) {
            return s.startsAt().isAfter(after) ? s.startsAt() : null;
        }
        ZoneId zone = ZoneId.of(s.zoneId());
        var start = s.localStartsAt() == null ? LocalDateTime.ofInstant(s.startsAt(), zone) : s.localStartsAt();
        LocalDate date = after.atZone(zone).toLocalDate();
        if (date.isBefore(start.toLocalDate())) {
            date = start.toLocalDate();
        }
        int step = s.repeat() == Repeat.INTERVAL ? s.intervalDays() : 1;
        if (s.repeat() == Repeat.INTERVAL) {
            long elapsed = ChronoUnit.DAYS.between(start.toLocalDate(), date);
            date = start.toLocalDate().plusDays((elapsed / step) * step);
        }
        for (int i = 0; i < 8; i++, date = date.plusDays(step)) {
            if (s.repeat() == Repeat.WEEKLY && !s.weekDays().contains(date.getDayOfWeek().getValue())) {
                continue;
            }
            Instant candidate = date.atTime(start.toLocalTime()).atZone(zone).toInstant();
            if (candidate.isBefore(s.startsAt()) || !candidate.isAfter(after)) {
                continue;
            }
            return s.endsAt() != null && !candidate.isBefore(s.endsAt()) ? null : candidate;
        }
        throw new IllegalStateException("Bounded campaign calendar could not advance");
    }

    public static SoundConnectException invalid() {
        return new SoundConnectException(ErrorType.INVALID_PARAMETER);
    }
}
