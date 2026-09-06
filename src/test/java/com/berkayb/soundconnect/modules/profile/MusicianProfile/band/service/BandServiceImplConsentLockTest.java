package com.berkayb.soundconnect.modules.profile.MusicianProfile.band.service;

import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.repository.ArtistVenueConnectionRequestRepository;
import com.berkayb.soundconnect.modules.event.performer.service.EventPerformerRequestService;
import com.berkayb.soundconnect.modules.event.publication.EventMemberPublicationRepository;
import com.berkayb.soundconnect.modules.event.entity.Event;
import com.berkayb.soundconnect.modules.event.enums.EventPerformerApprovalStatus;
import com.berkayb.soundconnect.modules.event.repository.EventRepository;
import com.berkayb.soundconnect.modules.follow.band.repository.BandFollowRepository;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.Band;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.BandMember;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandMemberShipStatus;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandRole;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.mapper.BandMapper;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository.BandMemberRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository.BandRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.support.BandEntityFinder;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.repository.MusicianProfileRepository;
import com.berkayb.soundconnect.modules.setlistcreator.repository.SetlistRepository;
import com.berkayb.soundconnect.modules.track.repository.TrackRepository;
import com.berkayb.soundconnect.modules.user.support.UserEntityFinder;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationProducer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;

class BandServiceImplConsentLockTest {

	@Mock private BandRepository bandRepository;
	@Mock private BandMemberRepository bandMemberRepository;
	@Mock private UserEntityFinder userEntityFinder;
	@Mock private BandEntityFinder bandEntityFinder;
	@Mock private BandMapper bandMapper;
	@Mock private MusicianProfileRepository musicianProfileRepository;
	@Mock private MediaAssetService mediaAssetService;
	@Mock private NotificationProducer notificationProducer;
	@Mock private BandFollowRepository bandFollowRepository;
	@Mock private ArtistVenueConnectionRequestRepository artistVenueConnectionRequestRepository;
	@Mock private SetlistRepository setlistRepository;
	@Mock private EventRepository eventRepository;
	@Mock private TrackRepository trackRepository;
	@Mock private EventPerformerRequestService eventPerformerRequestService;
	@Mock private EventMemberPublicationRepository eventMemberPublicationRepository;

	private BandServiceImpl service;

	@BeforeEach
	void setUp() {
		MockitoAnnotations.openMocks(this);
		service = new BandServiceImpl(
				bandRepository,
				bandMemberRepository,
				userEntityFinder,
				bandEntityFinder,
				bandMapper,
				musicianProfileRepository,
				mediaAssetService,
				notificationProducer,
				bandFollowRepository,
				artistVenueConnectionRequestRepository,
				setlistRepository,
				eventRepository,
				trackRepository,
				eventPerformerRequestService,
				eventMemberPublicationRepository
		);
	}

	@Test
	void deleteBandAcquiresAggregateFenceBeforeLockingLinkedEventsAndInvalidatingRequests() {
		UUID bandId = UUID.randomUUID();
		UUID founderId = UUID.randomUUID();
		Band band = new Band();
		band.setId(bandId);
		band.setName("Şahbaz");
		BandMember founder = BandMember.builder()
				.band(band)
				.bandRole(BandRole.FOUNDER)
				.status(BandMemberShipStatus.ACTIVE)
				.build();
		when(bandRepository.findByIdForUpdate(bandId)).thenReturn(Optional.of(band));
		when(bandMemberRepository.findByBandIdAndUserId(bandId, founderId)).thenReturn(Optional.of(founder));
		Event linkedEvent = Event.builder().band(band)
				.performerApprovalStatus(EventPerformerApprovalStatus.APPROVED)
				.profileCalendarApproved(true).build();
		when(eventRepository.findAllByBandIdForUpdate(bandId)).thenReturn(List.of(linkedEvent));

		service.deleteBand(bandId, founderId);

		InOrder order = inOrder(bandRepository, eventRepository, eventPerformerRequestService);
		order.verify(bandRepository).findByIdForUpdate(bandId);
		order.verify(eventRepository).findAllByBandIdForUpdate(bandId);
		order.verify(eventPerformerRequestService).invalidateForBand(bandId);
		verify(bandRepository).delete(band);
		assertThat(linkedEvent.getBand()).isNull();
		assertThat(linkedEvent.getManualPerformerName()).isEqualTo("Şahbaz");
		assertThat(linkedEvent.getPerformerApprovalStatus()).isEqualTo(EventPerformerApprovalStatus.NOT_REQUIRED);
		assertThat(linkedEvent.isProfileCalendarApproved()).isFalse();
	}
}
