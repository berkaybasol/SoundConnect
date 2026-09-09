package com.berkayb.soundconnect.modules.event.service;

import com.berkayb.soundconnect.modules.event.dto.request.EventCreateRequestDto;
import com.berkayb.soundconnect.modules.event.dto.response.EventResponseDto;
import com.berkayb.soundconnect.modules.event.entity.Event;
import com.berkayb.soundconnect.modules.event.mapper.EventMapper;
import com.berkayb.soundconnect.modules.event.repository.EventRepository;
import com.berkayb.soundconnect.modules.event.enums.EventPerformerApprovalStatus;
import com.berkayb.soundconnect.modules.event.performer.service.EventPerformerRequestService;
import com.berkayb.soundconnect.modules.media.entity.MediaAsset;
import com.berkayb.soundconnect.modules.media.enums.MediaKind;
import com.berkayb.soundconnect.modules.media.enums.MediaOwnerType;
import com.berkayb.soundconnect.modules.media.enums.MediaStatus;
import com.berkayb.soundconnect.modules.media.enums.MediaVisibility;
import com.berkayb.soundconnect.modules.media.repository.MediaAssetRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.Band;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository.BandRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.service.MusicianProfileService;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.repository.MusicianProfileRepository;
import com.berkayb.soundconnect.modules.profile.VenueProfile.entity.VenueProfile;
import com.berkayb.soundconnect.modules.profile.VenueProfile.repository.VenueProfileRepository;
import com.berkayb.soundconnect.modules.user.support.UserEntityFinder;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.venue.enums.VenueStatus;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import com.berkayb.soundconnect.modules.venue.support.VenueEntityFinder;
import com.berkayb.soundconnect.modules.venue.repository.VenueRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.*;
import org.springframework.transaction.annotation.Transactional;
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
	private EventMapper eventMapper;
	@Mock
	private MediaAssetRepository mediaAssetRepository;
	@Mock
	private VenueProfileRepository venueProfileRepository;
	@Mock
	private MusicianProfileRepository musicianProfileRepository;
	@Mock
	private BandRepository bandRepository;
	@Mock
	private VenueRepository venueRepository;
	@Mock
	private EventPerformerRequestService eventPerformerRequestService;
	
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
		musician.setId(musicianId);
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
		when(musicianProfileRepository.lockActiveVenueConnection(musicianId, venueId)).thenReturn(Optional.of(1));
		when(eventRepository.save(any(Event.class))).thenReturn(event);
		when(eventMapper.toDto(event)).thenReturn(responseDto);
		
		// when
		var result = eventService.createEvent(userId, dto);
		
		// then
		assertThat(result).isEqualTo(responseDto);
		verify(eventRepository).save(any(Event.class));
		verify(eventMapper).toDto(event);
		ArgumentCaptor<Event> eventCaptor = ArgumentCaptor.forClass(Event.class);
		verify(eventRepository).save(eventCaptor.capture());
		assertThat(eventCaptor.getValue().getMusicianProfile()).isSameAs(musician);
		assertThat(eventCaptor.getValue().getPerformerApprovalStatus())
				.isEqualTo(EventPerformerApprovalStatus.APPROVED);
		assertThat(eventCaptor.getValue().isProfileCalendarApproved()).isFalse();
		verify(eventPerformerRequestService).createProfileVisibilityRequest(userId, event, musician, null);
	}

	@Test
	void createEvent_unconnectedMusicianCreatesPendingRequestWithoutPublicProfileLink() {
		UUID musicianId = UUID.randomUUID();
		var dto = new EventCreateRequestDto(
				"Indie Night", null, LocalDate.now(), LocalTime.of(20, 0), null,
				null, venueId, musicianId, null, null
		);
		User owner = new User();
		owner.setId(userId);
		venue.setOwner(owner);
		venue.setStatus(VenueStatus.APPROVED);
		User musicianUser = new User();
		musicianUser.setId(UUID.randomUUID());
		musicianUser.setUsername("bugrasahin");
		MusicianProfile musician = new MusicianProfile();
		musician.setId(musicianId);
		musician.setUser(musicianUser);
		Event saved = new Event();
		saved.setId(UUID.randomUUID());

		when(userEntityFinder.getUser(userId)).thenReturn(owner);
		when(venueEntityFinder.getVenue(venueId)).thenReturn(venue);
		when(musicianProfileService.getProfileEntity(musicianId)).thenReturn(musician);
		when(musicianProfileRepository.lockActiveVenueConnection(musicianId, venueId)).thenReturn(Optional.empty());
		when(eventRepository.save(any(Event.class))).thenAnswer(invocation -> {
			Event value = invocation.getArgument(0);
			value.setId(saved.getId());
			return value;
		});

		eventService.createEvent(userId, dto);

		ArgumentCaptor<Event> eventCaptor = ArgumentCaptor.forClass(Event.class);
		verify(eventRepository).save(eventCaptor.capture());
		Event created = eventCaptor.getValue();
		assertThat(created.getMusicianProfile()).isNull();
		assertThat(created.getBand()).isNull();
		assertThat(created.getManualPerformerName()).isEqualTo("bugrasahin");
		assertThat(created.getPerformerApprovalStatus()).isEqualTo(EventPerformerApprovalStatus.PENDING);
		verify(eventPerformerRequestService).createPendingRequest(userId, created, musician, null);
		assertThat(created.isProfileCalendarApproved()).isFalse();
		verify(eventPerformerRequestService, never()).createProfileVisibilityRequest(any(), any(), any(), any());
	}

	@Test
	void createEvent_connectedBandLinksImmediatelyButRequestsProfileVisibility() {
		UUID bandId = UUID.randomUUID();
		var dto = new EventCreateRequestDto(
				"Band Night", null, LocalDate.now(), LocalTime.of(20, 0), null,
				null, venueId, null, bandId, null
		);
		User owner = new User();
		owner.setId(userId);
		venue.setOwner(owner);
		venue.setStatus(VenueStatus.APPROVED);
		Band band = new Band();
		band.setId(bandId);
		band.setName("Şahbaz");
		Event saved = new Event();
		saved.setId(UUID.randomUUID());

		when(userEntityFinder.getUser(userId)).thenReturn(owner);
		when(venueEntityFinder.getVenue(venueId)).thenReturn(venue);
		when(bandRepository.findByIdForUpdate(bandId)).thenReturn(Optional.of(band));
		when(venueRepository.lockActiveBandConnection(venueId, bandId)).thenReturn(Optional.of(1));
		when(eventRepository.save(any(Event.class))).thenReturn(saved);

		eventService.createEvent(userId, dto);

		ArgumentCaptor<Event> eventCaptor = ArgumentCaptor.forClass(Event.class);
		verify(eventRepository).save(eventCaptor.capture());
		assertThat(eventCaptor.getValue().getBand()).isSameAs(band);
		assertThat(eventCaptor.getValue().getManualPerformerName()).isNull();
		assertThat(eventCaptor.getValue().getPerformerApprovalStatus())
				.isEqualTo(EventPerformerApprovalStatus.APPROVED);
		assertThat(eventCaptor.getValue().isProfileCalendarApproved()).isFalse();
		verify(eventPerformerRequestService).createProfileVisibilityRequest(userId, saved, null, band);
	}

	@Test
	void createEvent_unconnectedBandUsesFullNameSnapshotAndDoesNotLinkBand() {
		UUID bandId = UUID.randomUUID();
		var dto = new EventCreateRequestDto(
				"Band Night", null, LocalDate.now(), LocalTime.of(20, 0), null,
				null, venueId, null, bandId, null
		);
		User owner = new User();
		owner.setId(userId);
		venue.setOwner(owner);
		venue.setStatus(VenueStatus.APPROVED);
		Band band = new Band();
		band.setId(bandId);
		band.setName("Şahbaz");
		when(userEntityFinder.getUser(userId)).thenReturn(owner);
		when(venueEntityFinder.getVenue(venueId)).thenReturn(venue);
		when(bandRepository.findByIdForUpdate(bandId)).thenReturn(Optional.of(band));
		when(venueRepository.lockActiveBandConnection(venueId, bandId)).thenReturn(Optional.empty());
		when(eventRepository.save(any(Event.class))).thenAnswer(invocation -> {
			Event value = invocation.getArgument(0);
			value.setId(UUID.randomUUID());
			return value;
		});

		eventService.createEvent(userId, dto);

		ArgumentCaptor<Event> eventCaptor = ArgumentCaptor.forClass(Event.class);
		verify(eventRepository).save(eventCaptor.capture());
		Event created = eventCaptor.getValue();
		assertThat(created.getBand()).isNull();
		assertThat(created.getManualPerformerName()).isEqualTo("Şahbaz");
		assertThat(created.getPerformerApprovalStatus()).isEqualTo(EventPerformerApprovalStatus.PENDING);
		verify(eventPerformerRequestService).createPendingRequest(userId, created, null, band);
		assertThat(created.isProfileCalendarApproved()).isFalse();
	}

	@Test
	void createEvent_rejectsBlankGeneratedBandSnapshotBeforePersistence() {
		UUID bandId = UUID.randomUUID();
		var dto = new EventCreateRequestDto(
				"Band Night", null, LocalDate.now(), LocalTime.of(20, 0), null,
				null, venueId, null, bandId, null
		);
		User owner = new User();
		owner.setId(userId);
		venue.setOwner(owner);
		venue.setStatus(VenueStatus.APPROVED);
		Band band = new Band();
		band.setId(bandId);
		band.setName("   ");
		when(userEntityFinder.getUser(userId)).thenReturn(owner);
		when(venueEntityFinder.getVenue(venueId)).thenReturn(venue);
		when(bandRepository.findByIdForUpdate(bandId)).thenReturn(Optional.of(band));
		when(venueRepository.lockActiveBandConnection(venueId, bandId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> eventService.createEvent(userId, dto))
				.isInstanceOf(SoundConnectException.class)
				.extracting("errorType")
				.isEqualTo(ErrorType.INVALID_PARAMETER);
		verify(eventRepository, never()).save(any());
		verifyNoInteractions(eventPerformerRequestService);
	}

	@Test
	void createEvent_rejectsGeneratedMusicianSnapshotThatExceedsRequestColumn() {
		UUID musicianId = UUID.randomUUID();
		var dto = new EventCreateRequestDto(
				"Solo Night", null, LocalDate.now(), LocalTime.of(20, 0), null,
				null, venueId, musicianId, null, null
		);
		User owner = new User();
		owner.setId(userId);
		venue.setOwner(owner);
		venue.setStatus(VenueStatus.APPROVED);
		MusicianProfile musician = new MusicianProfile();
		musician.setId(musicianId);
		musician.setStageName("x".repeat(101));
		when(userEntityFinder.getUser(userId)).thenReturn(owner);
		when(venueEntityFinder.getVenue(venueId)).thenReturn(venue);
		when(musicianProfileService.getProfileEntity(musicianId)).thenReturn(musician);
		when(musicianProfileRepository.lockActiveVenueConnection(musicianId, venueId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> eventService.createEvent(userId, dto))
				.isInstanceOf(SoundConnectException.class)
				.extracting("errorType")
				.isEqualTo(ErrorType.INVALID_PARAMETER);
		verify(eventRepository, never()).save(any());
		verifyNoInteractions(eventPerformerRequestService);
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
		when(eventRepository.findByIdForUpdate(eventId)).thenReturn(Optional.of(event));
		
		eventService.deleteEventById(userId, eventId);
		
		verify(eventRepository).delete(event);
		verify(eventPerformerRequestService).deleteForEvent(eventId);
	}

	@ParameterizedTest
	@ValueSource(strings = {"19:59", "20:00"})
	void createEvent_rejectsAnEndThatDoesNotFollowItsStart(String endTime) {
		User owner = User.builder().id(userId).build();
		venue.setOwner(owner);
		venue.setStatus(VenueStatus.APPROVED);
		when(userEntityFinder.getUser(userId)).thenReturn(owner);
		when(venueEntityFinder.getVenue(venueId)).thenReturn(venue);
		var dto = new EventCreateRequestDto("Concert", null, LocalDate.of(2026, 9, 20),
				LocalTime.of(20, 0), LocalTime.parse(endTime), null, venueId, null, null, null);

		assertThatThrownBy(() -> eventService.createEvent(userId, dto))
				.isInstanceOf(SoundConnectException.class).extracting("errorType").isEqualTo(ErrorType.INVALID_PARAMETER);
		verify(eventRepository, never()).save(any());
		verifyNoInteractions(eventPerformerRequestService);
	}

	@Test
	void publicDetailDoesNotFallBackToTheUnrestrictedEntityLookup() {
		UUID hiddenEventId = UUID.randomUUID();
		when(eventRepository.findPublicById(hiddenEventId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> eventService.getEventById(hiddenEventId))
				.isInstanceOf(SoundConnectException.class).extracting("errorType").isEqualTo(ErrorType.EVENT_NOT_FOUND);
		verify(eventRepository, never()).findById(any());
		verifyNoInteractions(eventMapper);
	}
	
	@Test
	void deleteEventById_shouldThrowWhenNotFound() {
		when(userEntityFinder.getUser(userId)).thenReturn(new User());
		when(eventRepository.findByIdForUpdate(any())).thenReturn(Optional.empty());
		assertThatThrownBy(() -> eventService.deleteEventById(userId, UUID.randomUUID()))
				.isInstanceOf(SoundConnectException.class)
				.extracting("errorType")
				.isEqualTo(ErrorType.EVENT_NOT_FOUND);
	}

	@Test
	void deleteEventById_keepsPessimisticLockInsideTransaction() throws Exception {
		var method = EventServiceImpl.class.getMethod("deleteEventById", UUID.class, UUID.class);
		assertThat(method.getAnnotation(Transactional.class)).isNotNull();
	}

	@Test
	void venueCannotDeleteAcceptedMusicianOriginEvent() {
		User owner = User.builder().id(userId).build();
		venue.setOwner(owner);
		Event event = Event.builder().venue(venue)
				.eventOrigin(com.berkayb.soundconnect.modules.event.enums.EventOrigin.MUSICIAN)
				.organizerUserId(UUID.randomUUID()).build();
		event.setId(UUID.randomUUID());
		when(userEntityFinder.getUser(userId)).thenReturn(owner);
		when(eventRepository.findByIdForUpdate(event.getId())).thenReturn(Optional.of(event));
		assertThatThrownBy(() -> eventService.deleteEventById(userId, event.getId()))
				.isInstanceOf(SoundConnectException.class).extracting("errorType").isEqualTo(ErrorType.EVENT_NOT_FOUND);
		verify(eventRepository, never()).delete(any());
		verifyNoInteractions(eventPerformerRequestService);
	}
}
