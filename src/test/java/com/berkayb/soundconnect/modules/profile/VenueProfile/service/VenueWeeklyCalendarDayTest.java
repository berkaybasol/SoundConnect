package com.berkayb.soundconnect.modules.profile.VenueProfile.service;

import com.berkayb.soundconnect.modules.event.entity.Event;
import com.berkayb.soundconnect.modules.event.repository.EventRepository;
import com.berkayb.soundconnect.modules.event.support.EventScheduleClock;
import com.berkayb.soundconnect.modules.media.repository.MediaAssetRepository;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.profile.VenueProfile.entity.VenueProfile;
import com.berkayb.soundconnect.modules.profile.VenueProfile.mapper.VenueProfileMapper;
import com.berkayb.soundconnect.modules.profile.VenueProfile.repository.VenueProfileRepository;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import com.berkayb.soundconnect.modules.venue.repository.VenueRepository;
import com.berkayb.soundconnect.modules.venue.support.VenueEntityFinder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VenueWeeklyCalendarDayTest {
    @Mock VenueProfileRepository profiles;
    @Mock VenueRepository venues;
    @Mock VenueProfileMapper mapper;
    @Mock VenueEntityFinder finder;
    @Mock EventRepository events;
    @Mock MediaAssetService media;
    @Mock MediaAssetRepository mediaRepository;
    final MutableClock clock = new MutableClock();
    final UUID ownerId = UUID.randomUUID();
    final Venue venue = Venue.builder().id(UUID.randomUUID()).name("Takvim").build();
    VenueProfileServiceImpl service;

    @BeforeEach void setup() {
        service = new VenueProfileServiceImpl(profiles, venues, mapper, finder,
                events, media, mediaRepository, clock);
    }

    @Test void publicCalendarRollsAtIstanbulMidnightAndKeepsTodayAfterEndTime() {
        when(venues.findPubliclyVisibleById(venue.getId())).thenReturn(Optional.of(venue));
        when(profiles.findByVenueId(venue.getId())).thenReturn(Optional.of(VenueProfile.builder().venue(venue).build()));
        LocalDate oldDay = LocalDate.of(2026, 9, 8);
        Event ended = Event.builder().venue(venue).title("Bugün")
                .eventDate(oldDay).startTime(LocalTime.of(10, 0)).endTime(LocalTime.NOON).build();
        ended.setId(UUID.randomUUID());
        when(events.findPublicByVenueBetween(venue, oldDay, oldDay.plusDays(6)))
                .thenReturn(List.of(ended));
        when(events.findPublicByVenueBetween(venue, oldDay.plusDays(1), oldDay.plusDays(7)))
                .thenReturn(List.of());
        clock.value = Instant.parse("2026-09-08T20:59:59.999Z");
        assertThat(service.getPublicProfileDetail(venue.getId()).weeklyEvents()).singleElement()
                .satisfies(item -> assertThat(item.eventId()).isEqualTo(ended.getId()));
        clock.value = Instant.parse("2026-09-08T21:00:00Z");
        assertThat(service.getPublicProfileDetail(venue.getId()).weeklyEvents()).isEmpty();
        verify(events).findPublicByVenueBetween(venue, oldDay, oldDay.plusDays(6));
        verify(events).findPublicByVenueBetween(venue, oldDay.plusDays(1), oldDay.plusDays(7));
        verifyNoMoreInteractions(events);
    }

    @Test void ownerCalendarUsesIstanbulYearBoundaryNotUtcHostDate() {
        when(venues.findByIdAndOwnerId(venue.getId(), ownerId)).thenReturn(Optional.of(venue));
        when(profiles.findByVenueId(venue.getId())).thenReturn(Optional.of(VenueProfile.builder().venue(venue).build()));
        clock.value = Instant.parse("2026-12-31T21:00:00Z");
        service.getOwnerProfileDetail(ownerId, venue.getId());
        verify(events).findByVenueAndEventDateBetweenOrderByEventDateAscStartTimeAsc(
                venue, LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 7));
        verifyNoMoreInteractions(events);
    }

    @Test void ownerPrivacyStillRejectsBeforeAnyCalendarRead() {
        when(venues.findByIdAndOwnerId(venue.getId(), ownerId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getOwnerProfileDetail(ownerId, venue.getId()))
                .isInstanceOf(com.berkayb.soundconnect.shared.exception.SoundConnectException.class);
        verifyNoInteractions(events, profiles);
    }

    static final class MutableClock extends EventScheduleClock {
        Instant value = Instant.parse("2026-09-08T21:00:00Z");
        @Override public Instant instant() { return value; }
    }
}
