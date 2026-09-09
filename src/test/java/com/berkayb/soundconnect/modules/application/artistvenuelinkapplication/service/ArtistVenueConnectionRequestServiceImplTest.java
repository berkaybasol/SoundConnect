package com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.service;

import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.dto.request.ArtistVenueConnectionRequestCreateDto;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.dto.response.ArtistVenueConnectionRequestResponseDto;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.entity.ArtistVenueConnectionRequest;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.enums.RequestByType;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.enums.RequestStatus;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.mapper.ArtistVenueConnectionRequestMapper;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.repository.ArtistVenueConnectionRequestRepository;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.repository.ConnectionRequestRow;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.Band;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.BandMember;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandMemberShipStatus;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandRole;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository.BandMemberRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository.BandRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.repository.MusicianProfileRepository;
import com.berkayb.soundconnect.modules.profile.VenueProfile.repository.VenueProfileRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import com.berkayb.soundconnect.modules.venue.repository.VenueRepository;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.modules.notification.service.TransactionalNotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@Tag("service")
class ArtistVenueConnectionRequestServiceImplTest {
	
	@Mock ArtistVenueConnectionRequestRepository requestRepo;
	@Mock MusicianProfileRepository musicianRepo;
	@Mock VenueRepository venueRepo;
	@Mock ArtistVenueConnectionRequestMapper mapper;
	@Mock BandRepository bandRepo;
	@Mock BandMemberRepository bandMemberRepo;
	@Mock VenueProfileRepository venueProfileRepo;
	@Mock MediaAssetService mediaAssetService;
	@Mock TransactionalNotificationService notificationProducer;
	
	@InjectMocks
	ArtistVenueConnectionRequestServiceImpl service;
	
	UUID mpId;
	UUID venueId;
	UUID musicianUserId;
	UUID venueOwnerId;
	
	// mock’lar
	MusicianProfile mp;
	Venue venue;
	
	@BeforeEach
	void init() {
		mpId = UUID.randomUUID();
		venueId = UUID.randomUUID();
		musicianUserId = UUID.randomUUID();
		venueOwnerId = UUID.randomUUID();
		
		mp = mock(MusicianProfile.class);
		venue = mock(Venue.class);
		User musicianUser = new User();
		musicianUser.setId(musicianUserId);
		User venueOwner = new User();
		venueOwner.setId(venueOwnerId);
		
		// id/stageName/name
		when(mp.getId()).thenReturn(mpId);
		when(mp.getStageName()).thenReturn("Stage X");
		when(mp.getUser()).thenReturn(musicianUser);
		when(venue.getId()).thenReturn(venueId);
		when(venue.getName()).thenReturn("Venue X");
		when(venue.getOwner()).thenReturn(venueOwner);
		when(requestRepo.findVenueByIdForUpdate(venueId)).thenReturn(Optional.of(venue));
		when(venueRepo.existsPubliclyVisibleById(any())).thenReturn(true);
		when(requestRepo.lockUsableAccountIds(anyCollection())).thenAnswer(invocation -> new ArrayList<>(invocation.<Collection<UUID>>getArgument(0)));
		
		// ilişki set’lerini gerçek set’lerle döndür
		Set<Venue> activeVenues = new HashSet<>();
		when(mp.getActiveVenues()).thenReturn(activeVenues);
		
		Set<MusicianProfile> activeMusicians = new HashSet<>();
		when(venue.getActiveMusicians()).thenReturn(activeMusicians);
	}
	
	@Test
	void malformedCreateCommandsFailWithoutLocksOrNotifications() {
		for (ArtistVenueConnectionRequestCreateDto invalid : new ArtistVenueConnectionRequestCreateDto[]{
				null,
				new ArtistVenueConnectionRequestCreateDto(mpId, null, null, "hi"),
				new ArtistVenueConnectionRequestCreateDto(mpId, null, venueId, "x".repeat(256))}) {
			assertThatThrownBy(() -> service.createRequest(musicianUserId, invalid, RequestByType.ARTIST))
					.isInstanceOfSatisfying(SoundConnectException.class, exception ->
							assertThat(exception.getErrorType()).isEqualTo(ErrorType.VALIDATION_ERROR));
		}
		verify(requestRepo, never()).findVenueByIdForUpdate(any());
		verify(requestRepo, never()).save(any());
		verifyNoInteractions(notificationProducer);
	}

	@Test
	void unavailableVenueCannotReceiveANewConnectionRequest() {
		when(musicianRepo.findById(mpId)).thenReturn(Optional.of(mp));
		when(venueRepo.existsPubliclyVisibleById(venueId)).thenReturn(false);
		var dto = new ArtistVenueConnectionRequestCreateDto(mpId, null, venueId, "hello");
		assertThatThrownBy(() -> service.createRequest(musicianUserId, dto, RequestByType.ARTIST))
				.isInstanceOfSatisfying(SoundConnectException.class, exception ->
						assertThat(exception.getErrorType()).isEqualTo(ErrorType.REQUEST_PARTICIPANT_UNAVAILABLE));
		verify(requestRepo, never()).save(any());
		verifyNoInteractions(notificationProducer);
	}

	@Test
	void venueThatBecameUnavailableCannotAcceptAnEarlierRequest() {
		UUID requestId = UUID.randomUUID();
		ArtistVenueConnectionRequest request = ArtistVenueConnectionRequest.builder()
				.venue(venue).musicianProfile(mp).requestByType(RequestByType.ARTIST).status(RequestStatus.PENDING).build();
		request.setId(requestId);
		when(requestRepo.findByIdForUpdate(requestId)).thenReturn(Optional.of(request));
		when(venueRepo.existsPubliclyVisibleById(venueId)).thenReturn(false);
		assertThatThrownBy(() -> service.acceptRequest(venueOwnerId, requestId))
				.isInstanceOfSatisfying(SoundConnectException.class, exception ->
						assertThat(exception.getErrorType()).isEqualTo(ErrorType.REQUEST_PARTICIPANT_UNAVAILABLE));
		assertThat(request.getStatus()).isEqualTo(RequestStatus.PENDING);
		assertThat(mp.getActiveVenues()).isEmpty();
		verify(requestRepo, never()).save(any());
		verifyNoInteractions(notificationProducer);
	}

	@Test
	void createRequest_ok() {
		var dto = new ArtistVenueConnectionRequestCreateDto(mpId, null, venueId, "hi");
		
		when(requestRepo.existsByMusicianProfileIdAndVenueIdAndStatus(mpId, venueId, RequestStatus.PENDING))
				.thenReturn(false);
		when(musicianRepo.findById(mpId)).thenReturn(Optional.of(mp));
		when(venueRepo.findById(venueId)).thenReturn(Optional.of(venue));
		
		var saved = new ArtistVenueConnectionRequest();
		saved.setId(UUID.randomUUID());
		saved.setMusicianProfile(mp);
		saved.setVenue(venue);
		saved.setStatus(RequestStatus.PENDING);
		saved.setRequestByType(RequestByType.ARTIST);
		saved.setMessage("hi");
		
		when(requestRepo.save(any())).thenReturn(saved);
		when(mapper.toResponseDto(saved))
				.thenReturn(new ArtistVenueConnectionRequestResponseDto(
						saved.getId(), mpId, null, venueId, "Stage X", null, null, "Venue X",
						"hi", RequestStatus.PENDING.name(), RequestByType.ARTIST, null
				));
		
		var res = service.createRequest(musicianUserId, dto, RequestByType.ARTIST);
		
		assertThat(res).isNotNull();
		assertThat(res.musicianProfileId()).isEqualTo(mpId);
		assertThat(res.venueId()).isEqualTo(venueId);
		
		verify(requestRepo).existsByMusicianProfileIdAndVenueIdAndStatus(mpId, venueId, RequestStatus.PENDING);
		verify(musicianRepo).findById(mpId);
		verify(requestRepo).findVenueByIdForUpdate(venueId);
		verify(requestRepo).save(any(ArtistVenueConnectionRequest.class));
		verify(mapper).toResponseDto(saved);
	}

	@Test
	void venueCanCreateAndBandManagerCanAcceptBandTargetRequest() {
		UUID bandId = UUID.randomUUID();
		UUID bandManagerId = UUID.randomUUID();
		Band band = mock(Band.class);
		User bandManager = new User();
		bandManager.setId(bandManagerId);
		BandMember managerMembership = BandMember.builder()
		                                         .band(band)
		                                         .user(bandManager)
		                                         .bandRole(BandRole.MANAGER)
		                                         .status(BandMemberShipStatus.ACTIVE)
		                                         .build();

		when(band.getId()).thenReturn(bandId);
		when(band.getName()).thenReturn("Band X");
		when(band.getMembers()).thenReturn(Set.of(managerMembership));
		when(venue.getActiveBands()).thenReturn(new HashSet<>());
		when(venueRepo.findById(venueId)).thenReturn(Optional.of(venue));
		when(bandRepo.findById(bandId)).thenReturn(Optional.of(band));
		when(bandRepo.findByIdForUpdate(bandId)).thenReturn(Optional.of(band));
		when(bandMemberRepo.findByBandId(bandId)).thenReturn(List.of(managerMembership));
		when(requestRepo.existsByBandIdAndVenueIdAndStatus(bandId, venueId, RequestStatus.PENDING))
				.thenReturn(false);

		ArgumentCaptor<ArtistVenueConnectionRequest> requestCaptor =
				ArgumentCaptor.forClass(ArtistVenueConnectionRequest.class);
		when(requestRepo.save(requestCaptor.capture())).thenAnswer(invocation -> {
			ArtistVenueConnectionRequest request = invocation.getArgument(0);
			if (request.getId() == null) request.setId(UUID.randomUUID());
			return request;
		});
		when(mapper.toResponseDto(any())).thenAnswer(invocation -> {
			ArtistVenueConnectionRequest request = invocation.getArgument(0);
			return new ArtistVenueConnectionRequestResponseDto(
					request.getId(), null, bandId, venueId, null, "Band X", null, "Venue X",
					request.getMessage(), request.getStatus().name(), request.getRequestByType(), null
			);
		});

		var created = service.createRequest(
				venueOwnerId,
				new ArtistVenueConnectionRequestCreateDto(null, bandId, venueId, "hello"),
				RequestByType.VENUE
		);
		ArtistVenueConnectionRequest savedRequest = requestCaptor.getValue();

		assertThat(created.bandId()).isEqualTo(bandId);
		assertThat(savedRequest.getBand()).isSameAs(band);
		assertThat(savedRequest.getMusicianProfile()).isNull();
		assertThat(savedRequest.getRequestByType()).isEqualTo(RequestByType.VENUE);

		when(requestRepo.findByIdForUpdate(savedRequest.getId())).thenReturn(Optional.of(savedRequest));
		var accepted = service.acceptRequest(bandManagerId, savedRequest.getId());

		assertThat(accepted.status()).isEqualTo(RequestStatus.ACCEPTED.name());
		assertThat(venue.getActiveBands()).contains(band);
		verify(venueRepo).save(venue);
	}
	
	@Test
	void createRequest_should_throw_when_duplicate_pending() {
		var dto = new ArtistVenueConnectionRequestCreateDto(mpId, null, venueId, null);
		when(musicianRepo.findById(mpId)).thenReturn(Optional.of(mp));
		when(venueRepo.findById(venueId)).thenReturn(Optional.of(venue));
		when(requestRepo.existsByMusicianProfileIdAndVenueIdAndStatus(mpId, venueId, RequestStatus.PENDING))
				.thenReturn(true);
		
		assertThatThrownBy(() -> service.createRequest(musicianUserId, dto, RequestByType.ARTIST))
				.isInstanceOf(SoundConnectException.class);
		
		verify(musicianRepo).findById(mpId);
		verify(requestRepo).findVenueByIdForUpdate(venueId);
		verify(requestRepo, never()).save(any());
	}
	
	@Test
	void createRequest_should_throw_when_musician_not_found() {
		var dto = new ArtistVenueConnectionRequestCreateDto(mpId, null, venueId, null);
		when(venueRepo.findById(venueId)).thenReturn(Optional.of(venue));
		when(requestRepo.existsByMusicianProfileIdAndVenueIdAndStatus(mpId, venueId, RequestStatus.PENDING))
				.thenReturn(false);
		when(musicianRepo.findById(mpId)).thenReturn(Optional.empty());
		
		assertThatThrownBy(() -> service.createRequest(musicianUserId, dto, RequestByType.ARTIST))
				.isInstanceOf(SoundConnectException.class);
		
		verify(requestRepo).findVenueByIdForUpdate(venueId);
		verify(requestRepo, never()).save(any());
	}
	
	@Test
	void createRequest_should_throw_when_venue_not_found() {
		var dto = new ArtistVenueConnectionRequestCreateDto(mpId, null, venueId, null);
		when(requestRepo.existsByMusicianProfileIdAndVenueIdAndStatus(mpId, venueId, RequestStatus.PENDING))
				.thenReturn(false);
		when(musicianRepo.findById(mpId)).thenReturn(Optional.of(mp));
		when(venueRepo.findById(venueId)).thenReturn(Optional.empty());
		when(requestRepo.findVenueByIdForUpdate(venueId)).thenReturn(Optional.empty());
		
		assertThatThrownBy(() -> service.createRequest(musicianUserId, dto, RequestByType.ARTIST))
				.isInstanceOf(SoundConnectException.class);
		
		verify(requestRepo, never()).save(any());
	}
	
	@Test
	void acceptRequest_ok_should_set_relations_and_status() {
		var req = new ArtistVenueConnectionRequest();
		req.setId(UUID.randomUUID());
		req.setMusicianProfile(mp);
		req.setVenue(venue);
		req.setStatus(RequestStatus.PENDING);
		
		when(requestRepo.findByIdForUpdate(req.getId())).thenReturn(Optional.of(req));
		when(requestRepo.save(req)).thenReturn(req);
		when(mapper.toResponseDto(req)).thenReturn(
				new ArtistVenueConnectionRequestResponseDto(
						req.getId(), mpId, null, venueId, "Stage X", null, null, "Venue X",
						null, RequestStatus.ACCEPTED.name(), RequestByType.ARTIST, null
				)
		);
		
		req.setRequestByType(RequestByType.ARTIST);
		var res = service.acceptRequest(venueOwnerId, req.getId());
		
		assertThat(req.getStatus()).isEqualTo(RequestStatus.ACCEPTED);
		assertThat(mp.getActiveVenues()).contains(venue);
		assertThat(venue.getActiveMusicians()).contains(mp);
		assertThat(res.status()).isEqualTo(RequestStatus.ACCEPTED.name());
		
		verify(musicianRepo).save(mp);
		verify(venueRepo).save(venue);
		verify(requestRepo).save(req);
		verify(mapper).toResponseDto(req);
	}
	
	@Test
	void acceptRequest_should_throw_when_already_accepted() {
		var req = new ArtistVenueConnectionRequest();
		req.setId(UUID.randomUUID());
		req.setMusicianProfile(mp);
		req.setVenue(venue);
		req.setStatus(RequestStatus.ACCEPTED);
		
		when(requestRepo.findByIdForUpdate(req.getId())).thenReturn(Optional.of(req));
		
		req.setRequestByType(RequestByType.ARTIST);
		assertThatThrownBy(() -> service.acceptRequest(venueOwnerId, req.getId()))
				.isInstanceOf(SoundConnectException.class);
		
		verifyNoInteractions(musicianRepo, venueRepo);
		verify(requestRepo, never()).save(any());
	}
	
	@Test
	void rejectRequest_ok_should_set_status_rejected() {
		var req = new ArtistVenueConnectionRequest();
		req.setId(UUID.randomUUID());
		req.setMusicianProfile(mp);
		req.setVenue(venue);
		req.setStatus(RequestStatus.PENDING);
		
		when(requestRepo.findByIdForUpdate(req.getId())).thenReturn(Optional.of(req));
		when(requestRepo.save(req)).thenReturn(req);
		when(mapper.toResponseDto(req)).thenReturn(
				new ArtistVenueConnectionRequestResponseDto(
						req.getId(), mpId, null, venueId, "Stage X", null, null, "Venue X",
						null, RequestStatus.REJECTED.name(), RequestByType.ARTIST, null
				)
		);
		
		req.setRequestByType(RequestByType.ARTIST);
		var res = service.rejectRequest(venueOwnerId, req.getId());
		
		assertThat(req.getStatus()).isEqualTo(RequestStatus.REJECTED);
		assertThat(res.status()).isEqualTo(RequestStatus.REJECTED.name());
		
		verify(requestRepo).save(req);
		verify(mapper).toResponseDto(req);
	}
	
	@Test
	void rejectRequest_should_throw_when_already_rejected() {
		var req = new ArtistVenueConnectionRequest();
		req.setId(UUID.randomUUID());
		req.setMusicianProfile(mp);
		req.setVenue(venue);
		req.setStatus(RequestStatus.REJECTED);
		
		when(requestRepo.findByIdForUpdate(req.getId())).thenReturn(Optional.of(req));
		
		req.setRequestByType(RequestByType.ARTIST);
		assertThatThrownBy(() -> service.rejectRequest(venueOwnerId, req.getId()))
				.isInstanceOf(SoundConnectException.class);
		
		verify(requestRepo, never()).save(any());
	}
	
	@Test
	void getRequestByMusicianProfile_should_map_list() {
		var r1 = new ArtistVenueConnectionRequest();
		r1.setId(UUID.randomUUID());
		r1.setMusicianProfile(mp);
		r1.setVenue(venue);
		r1.setStatus(RequestStatus.PENDING);
		
		var r2 = new ArtistVenueConnectionRequest();
		r2.setId(UUID.randomUUID());
		r2.setMusicianProfile(mp);
		r2.setVenue(venue);
		r2.setStatus(RequestStatus.ACCEPTED);
		
		when(requestRepo.findAllByMusicianProfileId(mpId)).thenReturn(List.of(r1, r2));
		when(mapper.toResponseDto(r1)).thenReturn(new ArtistVenueConnectionRequestResponseDto(r1.getId(), mpId, null, venueId, "Stage X", null, null, "Venue X", null, r1.getStatus().name(), null, null));
		when(mapper.toResponseDto(r2)).thenReturn(new ArtistVenueConnectionRequestResponseDto(r2.getId(), mpId, null, venueId, "Stage X", null, null, "Venue X", null, r2.getStatus().name(), null, null));
		
		when(musicianRepo.findById(mpId)).thenReturn(Optional.of(mp));
		var list = service.getRequestByMusicianProfile(musicianUserId, mpId, null);
		
		assertThat(list).hasSize(2);
		verify(requestRepo).findAllByMusicianProfileId(mpId);
		verify(mapper).toResponseDto(r1);
		verify(mapper).toResponseDto(r2);
	}
	
	@Test
	void getRequestsByVenue_should_map_list() {
		var r1 = new ArtistVenueConnectionRequest();
		r1.setId(UUID.randomUUID());
		r1.setMusicianProfile(mp);
		r1.setVenue(venue);
		r1.setStatus(RequestStatus.PENDING);
		
		when(requestRepo.findAllByVenueId(venueId)).thenReturn(List.of(r1));
		when(mapper.toResponseDto(r1)).thenReturn(new ArtistVenueConnectionRequestResponseDto(r1.getId(), mpId, null, venueId, "Stage X", null, null, "Venue X", null, r1.getStatus().name(), null, null));
		
		when(venueRepo.findById(venueId)).thenReturn(Optional.of(venue));
		var list = service.getRequestsByVenue(venueOwnerId, venueId, null);
		
		assertThat(list).hasSize(1);
		verify(requestRepo).findAllByVenueId(venueId);
		verify(mapper).toResponseDto(r1);
	}

	@ParameterizedTest
	@EnumSource(RequestByType.class)
	void createRejectsAmbiguousArtistIdentity(RequestByType type) {
		var dto = new ArtistVenueConnectionRequestCreateDto(mpId, UUID.randomUUID(), venueId, null);
		assertError(() -> service.createRequest(musicianUserId, dto, type), ErrorType.REQUEST_BY_TYPE_REQUIRED);
		verifyNoInteractions(requestRepo, musicianRepo, bandRepo, notificationProducer);
	}

	@ParameterizedTest
	@CsvSource({"ARTIST,true", "VENUE,true", "BAND,false", "VENUE,false"})
	void alreadyAcceptedPairCannotCreateAnotherRequest(RequestByType type, boolean musicianTarget) {
		UUID bandId = UUID.randomUUID();
		Band band = bandWithMember(bandId, musicianUserId, BandRole.FOUNDER, BandMemberShipStatus.ACTIVE);
		when(musicianRepo.findById(mpId)).thenReturn(Optional.of(mp));
		when(bandRepo.findById(bandId)).thenReturn(Optional.of(band));
		when(bandRepo.findByIdForUpdate(bandId)).thenReturn(Optional.of(band));
		when(requestRepo.existsByMusicianProfileIdAndVenueIdAndStatus(mpId, venueId, RequestStatus.ACCEPTED)).thenReturn(true);
		when(requestRepo.existsByBandIdAndVenueIdAndStatus(bandId, venueId, RequestStatus.ACCEPTED)).thenReturn(true);
		var dto = new ArtistVenueConnectionRequestCreateDto(musicianTarget ? mpId : null,
				musicianTarget ? null : bandId, venueId, null);
		UUID actor = type == RequestByType.VENUE ? venueOwnerId : musicianUserId;
		assertError(() -> service.createRequest(actor, dto, type), ErrorType.REQUEST_ALREADY_ACCEPTED);
		verify(requestRepo, never()).save(any());
		verifyNoInteractions(notificationProducer);
	}

	@Test
	void unauthorizedMusicianCannotProbeWhetherOtherArtistsHavePendingRequests() {
		when(musicianRepo.findById(mpId)).thenReturn(Optional.of(mp));
		var dto = new ArtistVenueConnectionRequestCreateDto(mpId, null, venueId, null);
		assertError(() -> service.createRequest(UUID.randomUUID(), dto, RequestByType.ARTIST), ErrorType.FORBIDDEN_ACCESS);
		verify(requestRepo, never()).existsByMusicianProfileIdAndVenueIdAndStatus(any(), any(), any());
		verify(requestRepo, never()).save(any());
	}

	@ParameterizedTest
	@CsvSource({"MEMBER,ACTIVE", "FOUNDER,LEFT", "MANAGER,PENDING", "MANAGER,REJECTED"})
	void unauthorizedBandRepresentativeCannotReadPrivateRequestStateOnCreate(BandRole role, BandMemberShipStatus status) {
		UUID bandId = UUID.randomUUID();
		Band band = bandWithMember(bandId, musicianUserId, role, status);
		when(bandRepo.findById(bandId)).thenReturn(Optional.of(band));
		var dto = new ArtistVenueConnectionRequestCreateDto(null, bandId, venueId, null);
		assertError(() -> service.createRequest(musicianUserId, dto, RequestByType.BAND), ErrorType.FORBIDDEN_ACCESS);
		verify(requestRepo, never()).existsByBandIdAndVenueIdAndStatus(any(), any(), any());
		verify(requestRepo, never()).save(any());
	}

	@Test
	void acceptingLegacyPendingRequestCannotDuplicateAnAcceptedPair() {
		ArtistVenueConnectionRequest request = request(RequestStatus.PENDING, RequestByType.ARTIST);
		when(requestRepo.findByIdForUpdate(request.getId())).thenReturn(Optional.of(request));
		when(requestRepo.existsByMusicianProfileIdAndVenueIdAndStatus(mpId, venueId, RequestStatus.ACCEPTED)).thenReturn(true);
		assertError(() -> service.acceptRequest(venueOwnerId, request.getId()), ErrorType.REQUEST_ALREADY_ACCEPTED);
		assertThat(request.getStatus()).isEqualTo(RequestStatus.PENDING);
		assertThat(mp.getActiveVenues()).isEmpty();
		verify(requestRepo, never()).save(any());
		verifyNoInteractions(notificationProducer);
	}

	@Test
	void disconnectedRequestCannotRemoveAReconnectedRelationship() {
		ArtistVenueConnectionRequest staleRequest = request(RequestStatus.REJECTED, RequestByType.ARTIST);
		when(requestRepo.findByIdForUpdate(staleRequest.getId())).thenReturn(Optional.of(staleRequest));
		mp.getActiveVenues().add(venue);
		venue.getActiveMusicians().add(mp);
		assertError(() -> service.disconnect(musicianUserId, staleRequest.getId()), ErrorType.REQUEST_ALREADY_REJECTED);
		assertThat(mp.getActiveVenues()).containsExactly(venue);
		assertThat(venue.getActiveMusicians()).containsExactly(mp);
		verify(requestRepo, never()).save(any());
	}

	@Test
	void bandFounderCannotDecidePersonalRequestAddressedToAnotherMusician() {
		ArtistVenueConnectionRequest request = request(RequestStatus.PENDING, RequestByType.VENUE);
		when(requestRepo.findByIdForUpdate(request.getId())).thenReturn(Optional.of(request));
		assertError(() -> service.acceptRequest(UUID.randomUUID(), request.getId()), ErrorType.FORBIDDEN_ACCESS);
		verifyNoInteractions(bandRepo, bandMemberRepo, notificationProducer);
		verify(requestRepo, never()).save(any());
	}

	@Test
	void formerBandMemberCannotReadTheBandsRequests() {
		UUID bandId = UUID.randomUUID();
		Band band = bandWithMember(bandId, musicianUserId, BandRole.FOUNDER, BandMemberShipStatus.LEFT);
		when(bandRepo.findById(bandId)).thenReturn(Optional.of(band));
		assertError(() -> service.getRequestsByBand(musicianUserId, bandId, null), ErrorType.FORBIDDEN_ACCESS);
		verify(requestRepo, never()).findAllByBandId(any());
	}

	@Test
	void privateVenuePageBatchEnrichesCurrentIdentityAndKeepsMissingMediaPrivate() {
		UUID bandId = UUID.randomUUID(), musicianImage = UUID.randomUUID(), bandImage = UUID.randomUUID(), venueImage = UUID.randomUUID();
		ConnectionRequestRow musician = new ConnectionRequestRow(UUID.randomUUID(), mpId, null, venueId,
				"  Current Stage  ", null, "Venue", "private request", RequestStatus.PENDING, RequestByType.ARTIST,
				null, musicianImage, null, venueImage, "current_username");
		ConnectionRequestRow band = new ConnectionRequestRow(UUID.randomUUID(), null, bandId, venueId,
				null, "Band", "Venue", "private request", RequestStatus.PENDING, RequestByType.BAND,
				null, null, bandImage, venueImage, null);
		when(venueRepo.findById(venueId)).thenReturn(Optional.of(venue));
		when(requestRepo.findVenuePage(eq(venueId), eq(RequestStatus.PENDING), eq(List.of(RequestByType.ARTIST, RequestByType.BAND)), any()))
				.thenReturn(new PageImpl<>(List.of(musician, band), PageRequest.of(1, 20), 41));
		when(mediaAssetService.getDisplayUrlMap(anyList())).thenReturn(Map.of(musicianImage, "https://cdn.test/musician", venueImage, "https://cdn.test/venue"));
		var result = service.getVenuePage(venueOwnerId, venueId, RequestStatus.PENDING, true, 1, 20);
		assertThat(result.totalElements()).isEqualTo(41);
		assertThat(result.page()).isEqualTo(1);
		assertThat(result.last()).isFalse();
		assertThat(result.content().getFirst().musicianDisplayName()).isEqualTo("Current Stage");
		assertThat(result.content().getFirst().musicianUsername()).isEqualTo("current_username");
		assertThat(result.content().getFirst().musicianProfilePictureUrl()).isEqualTo("https://cdn.test/musician");
		assertThat(result.content().getLast().bandProfilePictureUrl()).isNull();
		ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
		verify(requestRepo).findVenuePage(eq(venueId), eq(RequestStatus.PENDING), anyList(), page.capture());
		assertThat(page.getValue().getSort()).isEqualTo(Sort.by(Sort.Direction.DESC, "createdAt", "id"));
		verify(mediaAssetService).getDisplayUrlMap(argThat(ids -> ids.size() == 3 && ids.containsAll(List.of(musicianImage, bandImage, venueImage))));
		verifyNoInteractions(mapper, musicianRepo, bandRepo, venueProfileRepo);
		verify(mediaAssetService, never()).getDisplayUrl(any());
	}

	@ParameterizedTest
	@CsvSource({"-1,20", "10001,20", "0,0", "0,101"})
	void pageBoundsAreRejectedBeforeThePrivateListQuery(int page, int size) {
		when(venueRepo.findById(venueId)).thenReturn(Optional.of(venue));
		assertError(() -> service.getVenuePage(venueOwnerId, venueId, null, null, page, size), ErrorType.REQUEST_PAGE_INVALID);
		verify(requestRepo, never()).findVenuePage(any(), any(), any(), any());
	}

	@Test
	void privateMusicianAndVenuePagesCannotBeReadByAnotherUser() {
		when(venueRepo.findById(venueId)).thenReturn(Optional.of(venue));
		when(musicianRepo.findById(mpId)).thenReturn(Optional.of(mp));
		assertError(() -> service.getMusicianPage(venueOwnerId, mpId, null, null, 0, 20), ErrorType.FORBIDDEN_ACCESS);
		assertError(() -> service.getVenuePage(musicianUserId, venueId, null, null, 0, 20), ErrorType.FORBIDDEN_ACCESS);
		verify(requestRepo, never()).findMusicianPage(any(), any(), any(), any());
		verify(requestRepo, never()).findVenuePage(any(), any(), any(), any());
	}

	private ArtistVenueConnectionRequest request(RequestStatus status, RequestByType by) {
		ArtistVenueConnectionRequest request = new ArtistVenueConnectionRequest();
		request.setId(UUID.randomUUID());
		request.setMusicianProfile(mp);
		request.setVenue(venue);
		request.setStatus(status);
		request.setRequestByType(by);
		return request;
	}

	private Band bandWithMember(UUID bandId, UUID actor, BandRole role, BandMemberShipStatus status) {
		Band band = mock(Band.class);
		when(band.getId()).thenReturn(bandId);
		when(bandRepo.findByIdForUpdate(bandId)).thenReturn(Optional.of(band));
		User user = new User();
		user.setId(actor);
		when(band.getMembers()).thenReturn(Set.of(BandMember.builder().band(band).user(user)
				.bandRole(role).status(status).build()));
		return band;
	}

	private void assertError(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, ErrorType error) {
		assertThatThrownBy(action).isInstanceOfSatisfying(SoundConnectException.class,
				exception -> assertThat(exception.getErrorType()).isEqualTo(error));
	}
}
