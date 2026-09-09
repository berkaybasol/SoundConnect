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
import org.mockito.*;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class VenueWeeklyEventPosterTest {
    @Mock VenueProfileRepository profiles;
    @Mock VenueRepository venues;
    @Mock VenueProfileMapper mapper;
    @Mock VenueEntityFinder finder;
    @Mock EventRepository events;
    @Mock MediaAssetService media;
    @Mock MediaAssetRepository mediaRepository;
    @Spy EventScheduleClock scheduleClock = new EventScheduleClock();
    @InjectMocks VenueProfileServiceImpl service;
    Venue venue;
    Event event;
    UUID poster = UUID.randomUUID();

    @BeforeEach void setup() {
        MockitoAnnotations.openMocks(this);
        venue = Venue.builder().id(UUID.randomUUID()).name("Ankara").build();
        when(venues.findPubliclyVisibleById(venue.getId())).thenReturn(Optional.of(venue));
        when(profiles.findByVenueId(venue.getId())).thenReturn(Optional.of(VenueProfile.builder().venue(venue).build()));
        event = Event.builder().venue(venue).title("Gece").eventDate(LocalDate.now()).startTime(LocalTime.NOON).posterImage(poster.toString()).build();
        event.setId(UUID.randomUUID());
        when(events.findPublicByVenueBetween(eq(venue), any(), any())).thenReturn(List.of(event));
    }

    @Test void profileWeeklySummaryResolvesPosterUuidJustLikeCalendarDetails() {
        when(media.getDisplayUrl(poster)).thenReturn("https://cdn.test/event-poster.jpg");
        assertThat(service.getPublicProfileDetail(venue.getId()).weeklyEvents()).singleElement()
                .satisfies(item -> assertThat(item.posterImage()).isEqualTo("https://cdn.test/event-poster.jpg"));
    }

    @Test void unavailablePosterFallsBackWithoutHidingTheEvent() {
        when(media.getDisplayUrl(poster)).thenThrow(new IllegalStateException("unavailable"));
        assertThat(service.getPublicProfileDetail(venue.getId()).weeklyEvents()).singleElement()
                .satisfies(item -> { assertThat(item.posterImage()).isNull(); assertThat(item.eventId()).isEqualTo(event.getId()); });
    }
}
