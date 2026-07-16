package com.berkayb.soundconnect.modules.event.service;

import com.berkayb.soundconnect.modules.event.dto.request.EventCreateRequestDto;
import com.berkayb.soundconnect.modules.event.dto.response.EventResponseDto;
import com.berkayb.soundconnect.modules.event.entity.Event;
import com.berkayb.soundconnect.modules.event.mapper.EventMapper;
import com.berkayb.soundconnect.modules.event.repository.EventRepository;
import com.berkayb.soundconnect.modules.media.entity.MediaAsset;
import com.berkayb.soundconnect.modules.media.enums.MediaKind;
import com.berkayb.soundconnect.modules.media.enums.MediaOwnerType;
import com.berkayb.soundconnect.modules.media.enums.MediaStatus;
import com.berkayb.soundconnect.modules.media.enums.MediaVisibility;
import com.berkayb.soundconnect.modules.media.repository.MediaAssetRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.Band;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.service.BandService;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.service.MusicianProfileService;
import com.berkayb.soundconnect.modules.profile.VenueProfile.entity.VenueProfile;
import com.berkayb.soundconnect.modules.profile.VenueProfile.repository.VenueProfileRepository;
import com.berkayb.soundconnect.modules.user.support.UserEntityFinder;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.venue.enums.VenueStatus;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import com.berkayb.soundconnect.modules.venue.support.VenueEntityFinder;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.*;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Locale;
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
	@Mock
	private MediaAssetRepository mediaAssetRepository;
	@Mock
	private VenueProfileRepository venueProfileRepository;
	
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
				"poster.jpg", venueId, musicianId, null, null
		);
		
		var musician = new MusicianProfile();
		var event = new Event();
		event.setId(UUID.randomUUID());
		var responseDto = mock(EventResponseDto.class);
		
		User owner = new User();
		owner.setId(userId);
		venue.setOwner(owner);
		venue.setStatus(VenueStatus.APPROVED);
		when(userEntityFinder.getUser(userId)).thenReturn(owner);
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
				null, venueId, UUID.randomUUID(), UUID.randomUUID(), null
		);
		User owner = new User();
		owner.setId(userId);
		venue.setOwner(owner);
		venue.setStatus(VenueStatus.APPROVED);
		when(userEntityFinder.getUser(any())).thenReturn(owner);
		when(venueEntityFinder.getVenue(any())).thenReturn(venue);
		
		assertThatThrownBy(() -> eventService.createEvent(userId, dto))
				.isInstanceOf(SoundConnectException.class)
				.extracting("errorType")
				.isEqualTo(ErrorType.INVALID_PERFORMER_SELECTION);
	}

	@Test
	void createEvent_uuidPosterLocksAndValidatesVenueOwnedReadyImage() {
		UUID assetId = UUID.randomUUID();
		UUID venueProfileId = UUID.randomUUID();
		String nonCanonicalAssetId = "  " + assetId.toString().toUpperCase(Locale.ROOT) + "  ";
		var dto = new EventCreateRequestDto(
				"Poster event", null, LocalDate.now(), LocalTime.of(20, 0), null,
				nonCanonicalAssetId, venueId, null, null, "DJ"
		);
		User owner = new User();
		owner.setId(userId);
		venue.setOwner(owner);
		venue.setStatus(VenueStatus.APPROVED);
		VenueProfile venueProfile = new VenueProfile();
		venueProfile.setId(venueProfileId);
		MediaAsset poster = MediaAsset.builder()
				.id(assetId)
				.kind(MediaKind.IMAGE)
				.status(MediaStatus.READY)
				.visibility(MediaVisibility.PUBLIC)
				.ownerType(MediaOwnerType.VENUE_PROFILE)
				.ownerId(venueProfileId)
				.playbackUrl("https://cdn.test/poster.jpg")
				.build();
		Event saved = new Event();

		when(userEntityFinder.getUser(userId)).thenReturn(owner);
		when(venueEntityFinder.getVenue(venueId)).thenReturn(venue);
		when(mediaAssetRepository.findByIdForUpdate(assetId)).thenReturn(Optional.of(poster));
		when(venueProfileRepository.findByVenueId(venueId)).thenReturn(Optional.of(venueProfile));
		when(eventRepository.save(any(Event.class))).thenReturn(saved);

		eventService.createEvent(userId, dto);

		verify(mediaAssetRepository).findByIdForUpdate(assetId);
		ArgumentCaptor<Event> eventCaptor = ArgumentCaptor.forClass(Event.class);
		verify(eventRepository).save(eventCaptor.capture());
		assertThat(eventCaptor.getValue().getPosterImage()).isEqualTo(assetId.toString());
	}

	@Test
	void createEvent_legacyPosterPathRemainsUnchanged() {
		String legacyPosterPath = "  /legacy/event-posters/poster.jpg  ";
		var dto = new EventCreateRequestDto(
				"Legacy poster event", null, LocalDate.now(), LocalTime.of(20, 0), null,
				legacyPosterPath, venueId, null, null, "DJ"
		);
		User owner = new User();
		owner.setId(userId);
		venue.setOwner(owner);
		venue.setStatus(VenueStatus.APPROVED);
		Event saved = new Event();

		when(userEntityFinder.getUser(userId)).thenReturn(owner);
		when(venueEntityFinder.getVenue(venueId)).thenReturn(venue);
		when(eventRepository.save(any(Event.class))).thenReturn(saved);

		eventService.createEvent(userId, dto);

		ArgumentCaptor<Event> eventCaptor = ArgumentCaptor.forClass(Event.class);
		verify(eventRepository).save(eventCaptor.capture());
		assertThat(eventCaptor.getValue().getPosterImage()).isEqualTo(legacyPosterPath);
		verifyNoInteractions(mediaAssetRepository, venueProfileRepository);
	}
	
	@Test
	void deleteEventById_shouldDeleteSuccessfully() {
		UUID eventId = UUID.randomUUID();
		Event event = new Event();
		event.setId(eventId);
		User owner = new User();
		owner.setId(userId);
		venue.setOwner(owner);
		event.setVenue(venue);
		when(userEntityFinder.getUser(userId)).thenReturn(owner);
		when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));
		
		eventService.deleteEventById(userId, eventId);
		
		verify(eventRepository).delete(event);
	}
	
	@Test
	void deleteEventById_shouldThrowWhenNotFound() {
		when(userEntityFinder.getUser(userId)).thenReturn(new User());
		when(eventRepository.findById(any())).thenReturn(Optional.empty());
		assertThatThrownBy(() -> eventService.deleteEventById(userId, UUID.randomUUID()))
				.isInstanceOf(SoundConnectException.class)
				.extracting("errorType")
				.isEqualTo(ErrorType.EVENT_NOT_FOUND);
	}
}
