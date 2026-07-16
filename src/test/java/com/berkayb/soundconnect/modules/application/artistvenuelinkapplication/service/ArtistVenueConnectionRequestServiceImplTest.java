package com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.service;

import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.dto.request.ArtistVenueConnectionRequestCreateDto;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.dto.response.ArtistVenueConnectionRequestResponseDto;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.entity.ArtistVenueConnectionRequest;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.enums.RequestByType;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.enums.RequestStatus;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.mapper.ArtistVenueConnectionRequestMapper;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.repository.ArtistVenueConnectionRequestRepository;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository.BandMemberRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository.BandRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.repository.MusicianProfileRepository;
import com.berkayb.soundconnect.modules.profile.VenueProfile.repository.VenueProfileRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import com.berkayb.soundconnect.modules.venue.repository.VenueRepository;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationProducer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

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
	@Mock NotificationProducer notificationProducer;
	
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
		
		// ilişki set’lerini gerçek set’lerle döndür
		Set<Venue> activeVenues = new HashSet<>();
		when(mp.getActiveVenues()).thenReturn(activeVenues);
		
		Set<MusicianProfile> activeMusicians = new HashSet<>();
		when(venue.getActiveMusicians()).thenReturn(activeMusicians);
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
		verify(venueRepo).findById(venueId);
		verify(requestRepo).save(any(ArtistVenueConnectionRequest.class));
		verify(mapper).toResponseDto(saved);
	}
	
	@Test
	void createRequest_should_throw_when_duplicate_pending() {
		var dto = new ArtistVenueConnectionRequestCreateDto(mpId, null, venueId, null);
		when(venueRepo.findById(venueId)).thenReturn(Optional.of(venue));
		when(requestRepo.existsByMusicianProfileIdAndVenueIdAndStatus(mpId, venueId, RequestStatus.PENDING))
				.thenReturn(true);
		
		assertThatThrownBy(() -> service.createRequest(musicianUserId, dto, RequestByType.ARTIST))
				.isInstanceOf(SoundConnectException.class);
		
		verify(musicianRepo, never()).findById(any());
		verify(venueRepo).findById(venueId);
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
		
		verify(venueRepo).findById(venueId);
		verify(requestRepo, never()).save(any());
	}
	
	@Test
	void createRequest_should_throw_when_venue_not_found() {
		var dto = new ArtistVenueConnectionRequestCreateDto(mpId, null, venueId, null);
		when(requestRepo.existsByMusicianProfileIdAndVenueIdAndStatus(mpId, venueId, RequestStatus.PENDING))
				.thenReturn(false);
		when(musicianRepo.findById(mpId)).thenReturn(Optional.of(mp));
		when(venueRepo.findById(venueId)).thenReturn(Optional.empty());
		
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
		
		when(requestRepo.findById(req.getId())).thenReturn(Optional.of(req));
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
		
		when(requestRepo.findById(req.getId())).thenReturn(Optional.of(req));
		
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
		
		when(requestRepo.findById(req.getId())).thenReturn(Optional.of(req));
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
		
		when(requestRepo.findById(req.getId())).thenReturn(Optional.of(req));
		
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
}
