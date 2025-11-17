package com.berkayb.soundconnect.modules.event.service;

import com.berkayb.soundconnect.modules.event.dto.request.EventCreateRequestDto;
import com.berkayb.soundconnect.modules.event.dto.response.EventResponseDto;
import com.berkayb.soundconnect.modules.event.entity.Event;
import com.berkayb.soundconnect.modules.event.mapper.EventMapper;
import com.berkayb.soundconnect.modules.event.repository.EventRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.Band;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.service.BandService;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.service.MusicianProfileService;
import com.berkayb.soundconnect.modules.user.support.UserEntityFinder;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import com.berkayb.soundconnect.modules.venue.support.VenueEntityFinder;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.*;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class EventServiceImplTest {
	
	@Mock
	private EventRepository eventRepository;
	@Mock
	private VenueEntityFinder venueEntityFinder;
	@Mock
	private UserEntityFinder userEntityFinder;
	@Mock
	private MusicianProfileService musicianProfileService;
	@Mock
	private BandService bandService;
	@Mock
	private EventMapper eventMapper;
	
	@InjectMocks
	private EventServiceImpl eventService;
	
	private UUID userId;
	private UUID venueId;
	private Venue venue;
	
	@BeforeEach
	void setUp() {
		MockitoAnnotations.openMocks(this);
		userId = UUID.randomUUID();
		venueId = UUID.randomUUID();
		venue = new Venue();
		venue.setId(venueId);
	}
	
	@Test
	void createEvent_shouldCreateEventWithMusician() {
		// given
		UUID musicianId = UUID.randomUUID();
		var dto = new EventCreateRequestDto(
				"Rock Night", "Güzel bir konser", LocalDate.now(),
				LocalTime.of(20, 0), LocalTime.of(23, 0),
				"poster.jpg", venueId, musicianId, null
		);
		
		var musician = new MusicianProfile();
		var event = new Event();
		event.setId(UUID.randomUUID());
		var responseDto = mock(EventResponseDto.class);
		
		when(userEntityFinder.getUser(userId)).thenReturn(null);
		when(venueEntityFinder.getVenue(venueId)).thenReturn(venue);
		when(musicianProfileService.getProfileEntity(musicianId)).thenReturn(musician);
		when(eventRepository.save(any(Event.class))).thenReturn(event);
		when(eventMapper.toDto(event)).thenReturn(responseDto);
		
		// when
		var result = eventService.createEvent(userId, dto);
		
		// then
		assertThat(result).isEqualTo(responseDto);
		verify(eventRepository).save(any(Event.class));
		verify(eventMapper).toDto(event);
	}
	
	@Test
	void createEvent_shouldThrowExceptionWhenBothPerformerProvided() {
		var dto = new EventCreateRequestDto(
				"Title", null, LocalDate.now(), LocalTime.now(), null,
				null, venueId, UUID.randomUUID(), UUID.randomUUID()
		);
		when(userEntityFinder.getUser(any())).thenReturn(null);
		when(venueEntityFinder.getVenue(any())).thenReturn(venue);
		
		assertThatThrownBy(() -> eventService.createEvent(userId, dto))
				.isInstanceOf(SoundConnectException.class)
				.extracting("errorType")
				.isEqualTo(ErrorType.INVALID_PERFORMER_SELECTION);
	}
	
	@Test
	void deleteEventById_shouldDeleteSuccessfully() {
		UUID eventId = UUID.randomUUID();
		Event event = new Event();
		event.setId(eventId);
		when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));
		
		eventService.deleteEventById(eventId);
		
		verify(eventRepository).delete(event);
	}
	
	@Test
	void deleteEventById_shouldThrowWhenNotFound() {
		when(eventRepository.findById(any())).thenReturn(Optional.empty());
		assertThatThrownBy(() -> eventService.deleteEventById(UUID.randomUUID()))
				.isInstanceOf(SoundConnectException.class)
				.extracting("errorType")
				.isEqualTo(ErrorType.EVENT_NOT_FOUND);
	}
}