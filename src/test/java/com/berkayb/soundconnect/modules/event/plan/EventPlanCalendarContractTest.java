package com.berkayb.soundconnect.modules.event.plan;

import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.*;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class EventPlanCalendarContractTest {
    private static final UUID VENUE = UUID.randomUUID();
    private static final List<Integer> EVERY_DAY = List.of(1, 2, 3, 4, 5, 6, 7);

    @Test
    void fractionalTimesCannotBeSilentlyRoundedByPreviewOrOverrideNormalization() {
        for (LocalTime fraction : List.of(LocalTime.of(20, 0, 0, 1), LocalTime.of(20, 0, 0, 123456789),
                LocalTime.of(20, 0, 0, 999999999))) {
            assertThatThrownBy(() -> EventPlanRules.normalizeTemplate(new EventPlanTemplate("Concert", null,
                    fraction, null, null, null, null, "Performer"))).isInstanceOf(SoundConnectException.class);
            assertThatThrownBy(() -> EventPlanRules.normalizeTemplate(new EventPlanTemplate("Concert", null,
                    LocalTime.of(19, 0), fraction, null, null, null, "Performer"))).isInstanceOf(SoundConnectException.class);
        }
    }

    @Test
    void midnightUsesIstanbulCalendarAndNeverBackfillsStartedOccurrences() {
        var plan = definition(LocalDate.of(2026, 12, 1), null, EVERY_DAY, List.of(), LocalTime.of(0, 15));
        Instant before = Instant.parse("2026-12-31T21:14:59Z"); // Jan 1 00:14:59 in Istanbul.
        assertThat(EventPlanRules.dates(plan, before)).hasSize(28)
                .startsWith(LocalDate.of(2027, 1, 1)).endsWith(LocalDate.of(2027, 1, 28));
        assertThat(EventPlanRules.dates(plan, before.plusSeconds(1))).hasSize(27)
                .startsWith(LocalDate.of(2027, 1, 2));
    }

    @Test
    void finiteWeeklyPlanIncludesLeapDayAndItsInclusiveEndButNotExcludedDates() {
        var plan = definition(LocalDate.of(2028, 2, 21), LocalDate.of(2028, 3, 2), List.of(2, 4),
                List.of(LocalDate.of(2028, 2, 24)), LocalTime.of(20, 0));
        assertThat(EventPlanRules.dates(plan, Instant.parse("2028-02-21T08:00:00Z")))
                .containsExactly(LocalDate.of(2028, 2, 22), LocalDate.of(2028, 2, 29), LocalDate.of(2028, 3, 2));
        assertThat(EventPlanRules.hasFuture(plan, Instant.parse("2028-03-02T17:00:00Z"))).isFalse();
    }

    @Test
    void distantIndefinitePlanIsValidWithoutMaterializingThousandsOfRows() {
        var plan = definition(LocalDate.of(2027, 9, 1), null, List.of(5), List.of(), LocalTime.of(21, 0));
        Instant now = Instant.parse("2026-09-21T08:00:00Z");
        assertThat(EventPlanRules.dates(plan, now)).isEmpty();
        assertThat(EventPlanRules.hasFuture(plan, now)).isTrue();
        assertThat(EventPlanRules.previewDates(plan, now)).containsExactly(LocalDate.of(2027, 9, 3),
                LocalDate.of(2027, 9, 10), LocalDate.of(2027, 9, 17), LocalDate.of(2027, 9, 24));
    }

    @Test
    void aFinitePlanWithEveryOccurrenceExcludedCannotPretendItHasFutureDates() {
        LocalDate monday = LocalDate.of(2026, 9, 21);
        var plan = definition(monday, monday.plusDays(6), List.of(1, 5),
                List.of(monday, monday.plusDays(4)), LocalTime.of(20, 0));
        assertThat(EventPlanRules.hasFuture(plan, Instant.parse("2026-09-21T08:00:00Z"))).isFalse();
    }

    @Test
    void normalizationHasStableOrderAndDoesNotSilentlyAcceptAmbiguousDays() {
        LocalDate monday = LocalDate.of(2026, 9, 21);
        var value = definition(monday, null, List.of(5, 1), List.of(monday.plusDays(4), monday), LocalTime.of(20, 0));
        var normalized = EventPlanRules.normalize(value);
        assertThat(normalized.weekdays()).containsExactly(1, 5);
        assertThat(normalized.excludedDates()).containsExactly(monday, monday.plusDays(4));
        assertThatThrownBy(() -> EventPlanRules.normalize(definition(monday, null, List.of(1, 1), List.of(), LocalTime.NOON)))
                .isInstanceOf(SoundConnectException.class);
        assertThatThrownBy(() -> EventPlanRules.normalize(definition(monday, null, List.of(1), List.of(monday.plusDays(1)), LocalTime.NOON)))
                .isInstanceOf(SoundConnectException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 8, -1})
    void invalidWeekdaysFailRatherThanGeneratingAnUnexpectedSchedule(int weekday) {
        assertThatThrownBy(() -> EventPlanRules.normalize(definition(LocalDate.of(2026, 9, 21), null,
                List.of(weekday), List.of(), LocalTime.NOON))).isInstanceOf(SoundConnectException.class);
    }

    @Test
    void extendingConsentOrChangingThePerformerRequiresNewConsentButCopyEditsDoNot() {
        var original = definition(LocalDate.of(2026, 9, 21), LocalDate.of(2026, 10, 21),
                List.of(5), List.of(), LocalTime.of(20, 0));
        var extension = definition(original.startDate(), null, original.weekdays(), List.of(), LocalTime.of(20, 0));
        assertThat(EventPlanRules.consentScopeChanged(original, extension)).isTrue();
        var retitled = new EventPlanDefinition(original.venueId(), original.startDate(), original.untilDate(),
                original.weekdays(), original.excludedDates(), new EventPlanTemplate("Yeni başlık", "Yeni açıklama",
                original.template().startTime(), null, null, null, null, "Akustik ekip"));
        assertThat(EventPlanRules.consentScopeChanged(original, retitled)).isFalse();
        var replaced = new EventPlanTemplate("Konser", null, LocalTime.of(20, 0), null, null,
                UUID.randomUUID(), null, null);
        assertThat(EventPlanRules.performerOrScheduleChanged(original.template(), replaced)).isTrue();
    }

    private static EventPlanDefinition definition(LocalDate start, LocalDate until, List<Integer> days,
            List<LocalDate> excluded, LocalTime time) {
        return new EventPlanDefinition(VENUE, start, until, days, excluded,
                new EventPlanTemplate("Konser", null, time, null, null, null, null, "Akustik ekip"));
    }
}
