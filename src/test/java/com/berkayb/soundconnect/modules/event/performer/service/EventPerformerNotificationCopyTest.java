package com.berkayb.soundconnect.modules.event.performer.service;

import com.berkayb.soundconnect.modules.event.entity.Event;
import com.berkayb.soundconnect.modules.event.enums.EventPerformerApprovalStatus;
import com.berkayb.soundconnect.modules.event.performer.entity.EventPerformerRequest;
import com.berkayb.soundconnect.modules.event.performer.outbox.EventPerformerNotificationOutbox;
import com.berkayb.soundconnect.modules.event.performer.outbox.EventPerformerNotificationOutboxPublisher;
import com.berkayb.soundconnect.modules.event.performer.repository.EventPerformerRequestRepository;
import com.berkayb.soundconnect.modules.event.repository.EventRepository;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.Band;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.BandMember;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandMemberShipStatus;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandRole;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository.BandMemberRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository.BandRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.support.BandRepresentationPolicy;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.modules.profile.VenueProfile.repository.VenueProfileRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationInboundEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EventPerformerNotificationCopyTest {

	@Mock private EventPerformerRequestRepository requestRepository;
	@Mock private EventRepository eventRepository;
	@Mock private BandMemberRepository bandMemberRepository;
	@Mock private BandRepository bandRepository;
	@Mock private BandRepresentationPolicy bandRepresentationPolicy;
	@Mock private EventPerformerNotificationOutboxPublisher notificationOutboxPublisher;
	@Mock private VenueProfileRepository venueProfileRepository;
	@Mock private MediaAssetService mediaAssetService;
	@InjectMocks private EventPerformerRequestServiceImpl service;

	@Test
	void bandApprovalNamesTheGroupAndOnlyAsksActiveFounders() {
		Band band = band("Şahbaz");
		BandMember founder = member(band, BandRole.FOUNDER, BandMemberShipStatus.ACTIVE);
		BandMember activeMember = member(band, BandRole.MEMBER, BandMemberShipStatus.ACTIVE);
		BandMember formerFounder = member(band, BandRole.FOUNDER, BandMemberShipStatus.LEFT);
		when(bandMemberRepository.findByBandId(band.getId()))
				.thenReturn(List.of(founder, activeMember, formerFounder));
		stubRequestSave();

		service.createPendingRequest(UUID.randomUUID(), event(), null, band);

		List<NotificationInboundEvent> notifications = capturedNotifications(1).getFirst();
		assertThat(notifications).singleElement().satisfies(notification -> {
			assertThat(notification.recipientId()).isEqualTo(founder.getUser().getId());
			assertThat(notification.title()).isEqualTo("Grubunuz için etkinlik katılım onayı");
			assertThat(notification.message()).isEqualTo(
					"SoundConnect Ankara, “Gece Konseri” etkinliğine “Şahbaz” adlı grubunuzu eklemek istiyor."
			);
			assertThat(notification.type()).isEqualTo(NotificationType.EVENT_PERFORMER_APPROVAL_REQUESTED);
			assertThat(notification.payload())
					.containsEntry("performerType", "BAND")
					.containsEntry("bandId", band.getId().toString())
					.containsEntry("performerName", "Şahbaz")
					.containsEntry("availableActions", List.of("ACCEPT", "REJECT"))
					.doesNotContainKey("musicianProfileId");
		});
	}

	@Test
	void connectedBandAsksOnlyActiveFoundersForProfileVisibilityWithoutDuplicateRecipients() {
		Band band = band("Şahbaz'ın \"Akustik\" Grubu");
		BandMember founder = member(band, BandRole.FOUNDER, BandMemberShipStatus.ACTIVE);
		BandMember activeMember = member(band, BandRole.MEMBER, BandMemberShipStatus.ACTIVE);
		BandMember formerMember = member(band, BandRole.MEMBER, BandMemberShipStatus.LEFT);
		when(bandMemberRepository.findByBandId(band.getId()))
				.thenReturn(List.of(founder, founder, activeMember, activeMember, formerMember));
		stubRequestSave();
		Event event = linkedEvent(null, band);

		service.createProfileVisibilityRequest(UUID.randomUUID(), event, null, band);

		List<NotificationInboundEvent> notifications = capturedNotifications(1).getFirst();
		assertThat(notifications).extracting(NotificationInboundEvent::recipientId)
				.containsExactly(founder.getUser().getId());
		assertThat(notifications).allSatisfy(notification -> {
			assertThat(notification.title()).isEqualTo("Grubunuzun profilinde gösterilsin mi?");
			assertThat(notification.message()).isEqualTo(
					"SoundConnect Ankara, “Gece Konseri” etkinliğine “Şahbaz'ın \"Akustik\" Grubu” adlı grubunuzu ekledi. Etkinliğin grubunuzun profilindeki takvimde de gösterilmesini onaylıyor musun?"
			);
			assertThat(notification.type()).isEqualTo(NotificationType.EVENT_PERFORMER_APPROVAL_REQUESTED);
			assertThat(notification.payload())
					.containsEntry("performerName", band.getName())
					.containsEntry("bandId", band.getId().toString())
					.containsEntry("requestPurpose", "PROFILE_VISIBILITY")
					.containsEntry("availableActions", List.of("ACCEPT", "REJECT"))
					.doesNotContainKey("musicianProfileId");
		});
	}

	@Test
	void musicianApprovalRetainsPersonalWordingAndTargetsItsOwner() {
		MusicianProfile musician = musician();
		stubRequestSave();

		service.createPendingRequest(UUID.randomUUID(), event(), musician, null);

		assertThat(capturedNotifications(1).getFirst()).singleElement().satisfies(notification -> {
			assertThat(notification.recipientId()).isEqualTo(musician.getUser().getId());
			assertThat(notification.title()).isEqualTo("Etkinlik katılım onayı");
			assertThat(notification.message()).isEqualTo(
					"SoundConnect Ankara, “Gece Konseri” etkinliğine seni eklemek istiyor."
			);
			assertThat(notification.payload())
					.containsEntry("performerType", "MUSICIAN")
					.doesNotContainKey("bandId");
		});
	}

	@Test
	void connectedMusicianRetainsPersonalWording() {
		MusicianProfile musician = musician();
		stubRequestSave();

		service.createProfileVisibilityRequest(UUID.randomUUID(), linkedEvent(musician, null), musician, null);

		assertThat(capturedNotifications(1).getFirst()).singleElement().satisfies(notification -> {
			assertThat(notification.recipientId()).isEqualTo(musician.getUser().getId());
			assertThat(notification.title()).isEqualTo("Profilinde gösterilsin mi?");
			assertThat(notification.message()).isEqualTo(
					"SoundConnect Ankara, “Gece Konseri” etkinliğine seni ekledi. Etkinliğin profilindeki takvimde de gösterilmesini onaylıyor musun?"
			);
		});
	}

	@Test
	void maximumLegalBandEventAndVenueNamesRemainPersistableWithoutTruncation() {
		Band band = band("Ş".repeat(100));
		BandMember founder = member(band, BandRole.FOUNDER, BandMemberShipStatus.ACTIVE);
		when(bandMemberRepository.findByBandId(band.getId())).thenReturn(List.of(founder));
		stubRequestSave();
		Event event = event();
		event.setTitle("Ğ".repeat(255));
		event.getVenue().setName("İ".repeat(50));

		service.createPendingRequest(UUID.randomUUID(), event, null, band);
		event.setBand(band);
		event.setPerformerApprovalStatus(EventPerformerApprovalStatus.APPROVED);
		service.createProfileVisibilityRequest(UUID.randomUUID(), event, null, band);

		assertThat(capturedNotifications(2)).allSatisfy(batch ->
				assertThat(batch).singleElement().satisfies(notification -> {
					assertThat(notification.message()).contains(band.getName(), event.getTitle(), event.getVenue().getName());
					EventPerformerNotificationOutbox outbox = EventPerformerNotificationOutbox.pending(notification, Instant.now());
					assertThat(outbox.getMessage()).isEqualTo(notification.message()).hasSizeLessThanOrEqualTo(1000);
					assertThat(outbox.getTitle()).isEqualTo(notification.title()).hasSizeLessThanOrEqualTo(160);
				})
		);
	}

	private void stubRequestSave() {
		when(requestRepository.save(any(EventPerformerRequest.class))).thenAnswer(invocation -> {
			EventPerformerRequest request = invocation.getArgument(0);
			request.setId(UUID.randomUUID());
			return request;
		});
	}

	private List<List<NotificationInboundEvent>> capturedNotifications(int batchCount) {
		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<NotificationInboundEvent>> captor = ArgumentCaptor.forClass(List.class);
		verify(notificationOutboxPublisher, times(batchCount)).enqueueAll(captor.capture());
		return captor.getAllValues();
	}

	private Event event() {
		Venue venue = new Venue();
		venue.setId(UUID.randomUUID());
		venue.setName("SoundConnect Ankara");
		Event event = new Event();
		event.setId(UUID.randomUUID());
		event.setTitle("Gece Konseri");
		event.setVenue(venue);
		return event;
	}

	private Event linkedEvent(MusicianProfile musician, Band band) {
		Event event = event();
		event.setMusicianProfile(musician);
		event.setBand(band);
		event.setPerformerApprovalStatus(EventPerformerApprovalStatus.APPROVED);
		return event;
	}

	private Band band(String name) {
		Band band = new Band();
		band.setId(UUID.randomUUID());
		band.setName(name);
		return band;
	}

	private BandMember member(Band band, BandRole role, BandMemberShipStatus status) {
		User user = new User();
		user.setId(UUID.randomUUID());
		return BandMember.builder().band(band).user(user).bandRole(role).status(status).build();
	}

	private MusicianProfile musician() {
		User owner = new User();
		owner.setId(UUID.randomUUID());
		owner.setUsername("bugrasahin");
		MusicianProfile musician = new MusicianProfile();
		musician.setId(UUID.randomUUID());
		musician.setUser(owner);
		return musician;
	}
}
