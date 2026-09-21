package com.berkayb.soundconnect.modules.event.management;

import com.berkayb.soundconnect.modules.event.entity.Event;
import com.berkayb.soundconnect.modules.event.mapper.EventMapper;
import com.berkayb.soundconnect.modules.event.repository.EventRepository;
import com.berkayb.soundconnect.modules.event.support.EventScheduleClock;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.support.UserEntityFinder;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import com.berkayb.soundconnect.modules.venue.support.VenueEntityFinder;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EventManagementServiceTest {
    private final EventManagementRepository management = mock(EventManagementRepository.class);
    private final EventRepository events = mock(EventRepository.class);
    private final EventMapper mapper = mock(EventMapper.class);
    private final VenueEntityFinder venues = mock(VenueEntityFinder.class);
    private final UserEntityFinder users = mock(UserEntityFinder.class);
    private final EventScheduleClock clock = mock(EventScheduleClock.class);
    private final EventManagementService service = new EventManagementService(management, events, mapper, venues, users, clock);
    private final UUID actor = UUID.randomUUID(), venueId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-09-21T09:00:00Z");
    private final LocalDateTime localNow = LocalDateTime.of(2026, 9, 21, 12, 0);

    @BeforeEach void setUp() {
        User owner = new User(); owner.setId(actor);
        Venue venue = new Venue(); venue.setId(venueId); venue.setOwner(owner);
        when(users.getUser(actor)).thenReturn(owner);
        when(venues.getVenue(venueId)).thenReturn(venue);
        when(clock.instant()).thenReturn(now);
    }

    @Test void managementOnlyMapsUpcomingCardsAndCountsHistoryAtOneIstanbulBoundary() {
        UUID id = UUID.randomUUID(); Event event = new Event(); event.setId(id);
        when(management.upcomingIds(venueId, localNow)).thenReturn(List.of(id));
        when(management.pastCount(venueId, localNow)).thenReturn(120_000L);
        when(events.findOwnerCardsByIds(venueId, List.of(id))).thenReturn(List.of(event));
        when(mapper.toDtos(List.of(event))).thenReturn(List.of());
        var response = service.management(actor, venueId);
        assertThat(response.pastCount()).isEqualTo(120_000L);
        assertThat(response.historyAsOf()).isEqualTo(now);
        verify(management, never()).pastPositions(any(), any(), any(), anyInt());
        verify(mapper).toDtos(List.of(event));
        verify(events).findOwnerCardsByIds(venueId, List.of(id));
        verifyNoMoreInteractions(events);
    }

    @Test void historyUsesSizePlusOneButOnlyMapsTheRequestedPageAndRestoresDatabaseOrder() {
        var positions = IntStream.range(0, 21).mapToObj(i -> new EventHistoryCursor(LocalDate.of(2026, 9, 20),
                LocalTime.of(20, 0), UUID.randomUUID())).toList();
        when(management.pastPositions(venueId, localNow, null, 21)).thenReturn(positions);
        var ids = positions.subList(0, 20).stream().map(EventHistoryCursor::id).toList();
        var rows = ids.stream().map(id -> { Event event = new Event(); event.setId(id); return event; }).toList();
        when(events.findOwnerCardsByIds(venueId, ids)).thenReturn(rows.reversed());
        when(mapper.toDtos(rows)).thenReturn(List.of());
        var response = service.history(actor, venueId, now.toString(), null, 20);
        assertThat(response.hasNext()).isTrue();
        assertThat(EventHistoryCursor.decode(response.nextCursor(), venueId, now)).isEqualTo(positions.get(19));
        verify(mapper).toDtos(rows);
        verify(management, never()).pastCount(any(), any());
        verify(events).findOwnerCardsByIds(venueId, ids);
    }

    @Test void lastAndEmptyPagesHaveNoContinuation() {
        when(management.pastPositions(venueId, localNow, null, 21)).thenReturn(List.of());
        var response = service.history(actor, venueId, now.toString(), null, 20);
        assertThat(response.items()).isEmpty();
        assertThat(response.hasNext()).isFalse();
        assertThat(response.nextCursor()).isNull();
        verifyNoInteractions(events, mapper);
    }

    @Test void bothReadsRefuseOtherOwnersBeforeAnyHistoryQuery() {
        when(venues.getVenue(venueId)).thenReturn(new Venue());
        assertVenueNotFound(() -> service.management(actor, venueId));
        assertVenueNotFound(() -> service.history(actor, venueId, now.toString(), null, 20));
        verifyNoInteractions(management, events, mapper);
    }

    @ParameterizedTest @ValueSource(ints = {-1, 0, 51, 1000})
    void rejectsUnboundedPageSizes(int size) {
        assertInvalid(() -> service.history(actor, venueId, now.toString(), null, size));
        verifyNoInteractions(management, events, mapper);
    }

    @ParameterizedTest @ValueSource(strings = {"", "yesterday", "1969-12-31T23:59:59Z", "9999-12-31T23:59:59Z", "2026-09-21T10:00:00Z"})
    void rejectsMalformedOrFutureBoundaries(String asOf) {
        assertInvalid(() -> service.history(actor, venueId, asOf, null, 20));
        verifyNoInteractions(management, events, mapper);
    }

    @Test void cursorCannotBeReusedForAnotherVenueOrCutoffAndHasBoundedInput() {
        EventHistoryCursor position = new EventHistoryCursor(LocalDate.of(2026, 9, 20), LocalTime.NOON, UUID.randomUUID());
        assertInvalid(() -> service.history(actor, venueId, now.toString(), position.encode(UUID.randomUUID(), now), 20));
        assertInvalid(() -> service.history(actor, venueId, now.toString(), position.encode(venueId, now.minusSeconds(1)), 20));
        String oversizedDate = new EventHistoryCursor(LocalDate.MAX, LocalTime.NOON, UUID.randomUUID()).encode(venueId, now);
        assertInvalid(() -> service.history(actor, venueId, now.toString(), oversizedDate, 20));
        for (String raw : List.of("", "invalid-cursor", "a".repeat(513), "%%notbase64%%")) {
            assertInvalid(() -> service.history(actor, venueId, now.toString(), raw, 20));
        }
        verifyNoInteractions(management, events, mapper);
    }

    @Test void continuationKeepsOriginalBoundaryAndDoesNotRequireCursorEventToExist() {
        EventHistoryCursor position = new EventHistoryCursor(LocalDate.of(2026, 9, 20), LocalTime.NOON, UUID.randomUUID());
        Instant older = now.minusSeconds(600);
        when(management.pastPositions(venueId, localNow.minusMinutes(10), position, 21)).thenReturn(List.of());
        service.history(actor, venueId, older.toString(), position.encode(venueId, older), 20);
        verify(management).pastPositions(venueId, localNow.minusMinutes(10), position, 21);
        verifyNoInteractions(events);
    }

    private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(SoundConnectException.class,
                exception -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.INVALID_PARAMETER));
    }
    private static void assertVenueNotFound(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(SoundConnectException.class,
                exception -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.VENUE_NOT_FOUND));
    }
}
