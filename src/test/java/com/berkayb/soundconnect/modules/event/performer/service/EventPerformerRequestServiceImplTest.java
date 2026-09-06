package com.berkayb.soundconnect.modules.event.performer.service;

import com.berkayb.soundconnect.modules.event.entity.Event;
import com.berkayb.soundconnect.modules.event.enums.EventPerformerApprovalStatus;
import com.berkayb.soundconnect.modules.event.enums.PerformerType;
import com.berkayb.soundconnect.modules.event.performer.entity.EventPerformerRequest;
import com.berkayb.soundconnect.modules.event.performer.dto.EventPerformerRequestAuthorizationSnapshot;
import com.berkayb.soundconnect.modules.event.performer.dto.EventPerformerRequestLockTarget;
import com.berkayb.soundconnect.modules.event.performer.enums.EventPerformerRequestStatus;
import com.berkayb.soundconnect.modules.event.performer.enums.EventPerformerRequestPurpose;
import com.berkayb.soundconnect.modules.event.performer.outbox.EventPerformerNotificationOutboxPublisher;
import com.berkayb.soundconnect.modules.event.performer.repository.EventPerformerRequestRepository;
import com.berkayb.soundconnect.modules.event.repository.EventRepository;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.Band;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository.BandMemberRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository.BandRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.support.BandRepresentationPolicy;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.modules.profile.VenueProfile.repository.VenueProfileRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationInboundEvent;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Instant;
import com.berkayb.soundconnect.modules.event.support.EventScheduleClock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.any;

class EventPerformerRequestServiceImplTest {

	@Mock private EventPerformerRequestRepository requestRepository;
	@Mock private EventRepository eventRepository;
	@Mock private BandMemberRepository bandMemberRepository;
	@Mock private BandRepository bandRepository;
	@Mock private BandRepresentationPolicy bandRepresentationPolicy;
	@Mock private EventPerformerNotificationOutboxPublisher notificationOutboxPublisher;
	@Mock private VenueProfileRepository venueProfileRepository;
	@Mock private MediaAssetService mediaAssetService;

	private EventPerformerRequestServiceImpl service;
	private Instant now = Instant.parse("2026-09-06T09:00:00Z");
	private final EventScheduleClock clock = new EventScheduleClock() {
		@Override public Instant instant() { return now; }
	};

	@BeforeEach
	void setUp() {
		MockitoAnnotations.openMocks(this);
		service = new EventPerformerRequestServiceImpl(
				requestRepository,
				eventRepository,
				bandMemberRepository,
				bandRepository,
				bandRepresentationPolicy,
				notificationOutboxPublisher,
				venueProfileRepository,
				mediaAssetService,
				clock
		);
	}

	@Test
	void acceptLocksInDeleteCompatibleOrderAndAtomicallyLinksMusician() {
		UUID actorId = UUID.randomUUID();
		Fixture fixture = musicianFixture(actorId, EventPerformerRequestStatus.PENDING);
		stubLocks(fixture);

		var response = service.accept(actorId, fixture.request().getId());

		assertThat(response.status()).isEqualTo(EventPerformerRequestStatus.ACCEPTED);
		assertThat(fixture.event().getMusicianProfile()).isSameAs(fixture.musician());
		assertThat(fixture.event().getBand()).isNull();
		assertThat(fixture.event().getManualPerformerName()).isNull();
		assertThat(fixture.event().getPerformerApprovalStatus())
				.isEqualTo(EventPerformerApprovalStatus.APPROVED);
		assertThat(fixture.request().getDecidedByUserId()).isEqualTo(actorId);
		assertThat(fixture.request().getDecidedAt()).isNotNull();
		assertThat(fixture.event().isProfileCalendarApproved()).isFalse();
		assertThat(response.profileCalendarApproved()).isFalse();

		InOrder order = inOrder(eventRepository, requestRepository);
		order.verify(eventRepository).findByIdForUpdate(fixture.event().getId());
		order.verify(requestRepository).findByIdForUpdate(fixture.request().getId());
		verify(eventRepository).save(fixture.event());
		verify(requestRepository).save(fixture.request());
	}

	@ParameterizedTest @ValueSource(booleans = {false, true})
	void rejectedDecisionRequiresDedicatedPathAndOnlyOneReconsiderNotification(boolean publish) {
		UUID actor = UUID.randomUUID();
		Fixture f = musicianFixture(actor, EventPerformerRequestStatus.PENDING);
		stubLocks(f);
		assertThat(service.reject(actor, f.request().getId()).canReconsider()).isTrue();
		assertThatThrownBy(() -> service.accept(actor, f.request().getId(), publish))
				.isInstanceOf(SoundConnectException.class).extracting("errorType")
				.isEqualTo(ErrorType.EVENT_PERFORMER_REQUEST_FINALIZED);
		var result = service.reconsider(actor, f.request().getId(), publish);
		assertThat(result.status()).isEqualTo(EventPerformerRequestStatus.ACCEPTED);
		assertThat(result.profileCalendarApproved()).isEqualTo(publish);
		assertThat(result.canReconsider()).isFalse();
		assertThat(result.decisionAllowed()).isFalse();
		assertThat(f.event().getMusicianProfile()).isSameAs(f.musician());
		assertThat(f.event().getPerformerApprovalStatus()).isEqualTo(EventPerformerApprovalStatus.APPROVED);
		// An acceptance retry after subsequent hiding must not re-publish the profile.
		f.event().setProfileCalendarApproved(false);
		now = clock.startsAt(f.event()).plusSeconds(1);
		assertThat(service.reconsider(actor, f.request().getId(), publish).profileCalendarApproved()).isFalse();
		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<NotificationInboundEvent>> sent = ArgumentCaptor.forClass(List.class);
		verify(notificationOutboxPublisher, times(2)).enqueueAll(sent.capture());
		assertThat(sent.getAllValues().get(0).getFirst().eventId())
				.isNotEqualTo(sent.getAllValues().get(1).getFirst().eventId());
		verify(eventRepository, times(2)).save(f.event());
	}

	@ParameterizedTest @ValueSource(longs = {0, 1})
	void pendingAndRejectedDecisionsAreReadOnlyAtAndAfterStart(long secondsAfterStart) {
		UUID actor = UUID.randomUUID();
		Fixture f = musicianFixture(actor, EventPerformerRequestStatus.PENDING);
		stubLocks(f);
		now = clock.startsAt(f.event()).plusSeconds(secondsAfterStart);
		assertThatThrownBy(() -> service.accept(actor, f.request().getId(), true))
				.isInstanceOf(SoundConnectException.class).extracting("errorType").isEqualTo(ErrorType.EVENT_PERFORMER_REQUEST_EXPIRED);
		assertThatThrownBy(() -> service.reject(actor, f.request().getId()))
				.isInstanceOf(SoundConnectException.class).extracting("errorType").isEqualTo(ErrorType.EVENT_PERFORMER_REQUEST_EXPIRED);
		f.request().setStatus(EventPerformerRequestStatus.REJECTED);
		f.event().setPerformerApprovalStatus(EventPerformerApprovalStatus.REJECTED);
		assertThatThrownBy(() -> service.reconsider(actor, f.request().getId(), true))
				.isInstanceOf(SoundConnectException.class).extracting("errorType").isEqualTo(ErrorType.EVENT_PERFORMER_REQUEST_EXPIRED);
		var retry = service.reject(actor, f.request().getId());
		assertThat(retry.expired()).isTrue();
		assertThat(retry.canReconsider()).isFalse();
		assertThat(retry.serverNow()).isEqualTo(now);
		assertThat(retry.eventStartsAt()).isEqualTo(Instant.parse("2026-09-12T18:00:00Z"));
		verify(eventRepository, never()).save(any());
		verify(requestRepository, never()).save(any());
		verifyNoInteractions(notificationOutboxPublisher);
	}

	@Test void beforeStartUsesTurkeyTimeNotHostTimezone() {
		UUID actor = UUID.randomUUID();
		Fixture f = musicianFixture(actor, EventPerformerRequestStatus.PENDING);
		stubLocks(f);
		now = Instant.parse("2026-09-12T17:59:59.999999999Z");
		assertThat(service.accept(actor, f.request().getId(), false).status())
				.isEqualTo(EventPerformerRequestStatus.ACCEPTED);
	}

	@Test void deadlineIsCheckedAfterWaitingForTheEventLock() {
		UUID actor = UUID.randomUUID();
		Fixture f = musicianFixture(actor, EventPerformerRequestStatus.REJECTED);
		stubLocks(f);
		f.event().setPerformerApprovalStatus(EventPerformerApprovalStatus.REJECTED);
		now = clock.startsAt(f.event()).minusSeconds(1);
		when(eventRepository.findByIdForUpdate(f.event().getId())).thenAnswer(invocation -> {
			now = clock.startsAt(f.event());
			return Optional.of(f.event());
		});
		assertThatThrownBy(() -> service.reconsider(actor, f.request().getId(), true))
				.isInstanceOf(SoundConnectException.class).extracting("errorType").isEqualTo(ErrorType.EVENT_PERFORMER_REQUEST_EXPIRED);
		verify(eventRepository, never()).save(any());
		verifyNoInteractions(notificationOutboxPublisher);
	}

	@Test void reconsiderRequiresExplicitConsentAndDoesNotAcceptPendingInvitation() {
		UUID actor = UUID.randomUUID();
		Fixture f = musicianFixture(actor, EventPerformerRequestStatus.PENDING);
		stubLocks(f);
		assertThatThrownBy(() -> service.reconsider(actor, f.request().getId(), null))
				.isInstanceOf(SoundConnectException.class).extracting("errorType").isEqualTo(ErrorType.INVALID_PARAMETER);
		assertThatThrownBy(() -> service.reconsider(actor, f.request().getId(), false))
				.isInstanceOf(SoundConnectException.class).extracting("errorType").isEqualTo(ErrorType.EVENT_PERFORMER_REQUEST_FINALIZED);
		verifyNoInteractions(notificationOutboxPublisher);
	}

	@Test void rejectedRequestCannotLinkAnUnexpectedEventTarget() {
		UUID actor = UUID.randomUUID();
		Fixture f = musicianFixture(actor, EventPerformerRequestStatus.REJECTED);
		stubLocks(f);
		f.event().setMusicianProfile(f.musician());
		assertThatThrownBy(() -> service.reconsider(actor, f.request().getId(), true))
				.isInstanceOf(SoundConnectException.class).extracting("errorType").isEqualTo(ErrorType.EVENT_PERFORMER_REQUEST_INVALID);
		verifyNoInteractions(notificationOutboxPublisher);
	}

	@Test
	void acceptIsIdempotentForAcceptedRequest() {
		UUID actorId = UUID.randomUUID();
		Fixture fixture = musicianFixture(actorId, EventPerformerRequestStatus.ACCEPTED);
		fixture.request().setAcceptedProfilePublication(false);
		fixture.event().setMusicianProfile(fixture.musician());
		fixture.event().setManualPerformerName(null);
		fixture.event().setPerformerApprovalStatus(EventPerformerApprovalStatus.APPROVED);
		stubLocks(fixture);

		var response = service.accept(actorId, fixture.request().getId());

		assertThat(response.status()).isEqualTo(EventPerformerRequestStatus.ACCEPTED);
		verify(eventRepository, never()).save(fixture.event());
		verify(requestRepository, never()).save(fixture.request());
		verify(requestRepository, never()).findById(fixture.request().getId());
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void participationChoiceIsExplicitAndOnlyIdenticalRetriesAreIdempotent(boolean showOnProfile) {
		UUID actorId = UUID.randomUUID();
		Fixture fixture = musicianFixture(actorId, EventPerformerRequestStatus.PENDING);
		stubLocks(fixture);

		var first = service.accept(actorId, fixture.request().getId(), showOnProfile);
		var repeated = service.accept(actorId, fixture.request().getId(), showOnProfile);

		assertThat(first.profileCalendarApproved()).isEqualTo(showOnProfile);
		assertThat(repeated).isEqualTo(first);
		assertThat(fixture.event().isProfileCalendarApproved()).isEqualTo(showOnProfile);
		assertThat(fixture.event().getMusicianProfile()).isSameAs(fixture.musician());
		assertThat(fixture.event().getPerformerApprovalStatus()).isEqualTo(EventPerformerApprovalStatus.APPROVED);
		assertThatThrownBy(() -> service.accept(actorId, fixture.request().getId(), !showOnProfile))
				.isInstanceOf(SoundConnectException.class).extracting("errorType")
				.isEqualTo(ErrorType.EVENT_PERFORMER_REQUEST_FINALIZED);
		assertThat(fixture.event().isProfileCalendarApproved()).isEqualTo(showOnProfile);
		assertThat(fixture.request().getDecidedAt()).isEqualTo(first.decidedAt());
		verify(eventRepository).save(fixture.event());
		verify(requestRepository).save(fixture.request());
		verify(notificationOutboxPublisher).enqueueAll(any());
	}

	@Test
	void legacyAcceptedPublicationSurvivesBodylessRetryAndCannotBeSilentlyChanged() {
		UUID actorId = UUID.randomUUID();
		Fixture fixture = musicianFixture(actorId, EventPerformerRequestStatus.ACCEPTED);
		fixture.request().setAcceptedProfilePublication(true);
		fixture.event().setMusicianProfile(fixture.musician());
		fixture.event().setManualPerformerName(null);
		fixture.event().setPerformerApprovalStatus(EventPerformerApprovalStatus.APPROVED);
		fixture.event().setProfileCalendarApproved(true);
		stubLocks(fixture);

		assertThatThrownBy(() -> service.accept(actorId, fixture.request().getId()))
				.isInstanceOf(SoundConnectException.class).extracting("errorType")
				.isEqualTo(ErrorType.EVENT_PERFORMER_REQUEST_FINALIZED);
		assertThat(service.accept(actorId, fixture.request().getId(), true).profileCalendarApproved()).isTrue();
		assertThat(fixture.event().isProfileCalendarApproved()).isTrue();
		verify(eventRepository, never()).save(any());
		verify(requestRepository, never()).save(any());
		verifyNoInteractions(notificationOutboxPublisher);
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void originalAcceptanceRetryNeverChangesLaterProfilePublication(boolean originalChoice) {
		UUID actorId = UUID.randomUUID();
		Fixture fixture = musicianFixture(actorId, EventPerformerRequestStatus.PENDING);
		stubLocks(fixture);
		service.accept(actorId, fixture.request().getId(), originalChoice);
		fixture.event().setProfileCalendarApproved(!originalChoice);
		fixture.event().setProfilePublicationVersion(9);
		org.mockito.Mockito.clearInvocations(eventRepository, requestRepository, notificationOutboxPublisher);
		service.accept(actorId, fixture.request().getId(), originalChoice);
		assertThat(fixture.event().isProfileCalendarApproved()).isEqualTo(!originalChoice);
		assertThat(fixture.event().getProfilePublicationVersion()).isEqualTo(9);
		assertThat(fixture.request().getAcceptedProfilePublication()).isEqualTo(originalChoice);
		verify(eventRepository, never()).save(any());
		verify(requestRepository, never()).save(any());
		verifyNoInteractions(notificationOutboxPublisher);
	}

	@Test
	void visibilityOnlyFalseIsInvalidWithoutChangingConnectedLinkOrFinalizingRequest() {
		UUID actorId = UUID.randomUUID();
		Fixture fixture = connectedFixture(actorId);
		stubLocks(fixture);

		assertThatThrownBy(() -> service.accept(actorId, fixture.request().getId(), false))
				.isInstanceOf(SoundConnectException.class).extracting("errorType")
				.isEqualTo(ErrorType.INVALID_PARAMETER);
		assertThat(fixture.request().getStatus()).isEqualTo(EventPerformerRequestStatus.PENDING);
		assertThat(fixture.event().getMusicianProfile()).isSameAs(fixture.musician());
		assertThat(fixture.event().isProfileCalendarApproved()).isFalse();
		verify(eventRepository, never()).save(any());
		verify(requestRepository, never()).save(any());
		verifyNoInteractions(notificationOutboxPublisher);

		var accepted = service.accept(actorId, fixture.request().getId(), true);
		assertThat(accepted.profileCalendarApproved()).isTrue();
		assertThatThrownBy(() -> service.accept(actorId, fixture.request().getId(), false))
				.isInstanceOf(SoundConnectException.class).extracting("errorType")
				.isEqualTo(ErrorType.EVENT_PERFORMER_REQUEST_FINALIZED);
	}

	@Test
	void acceptAfterRejectConflicts() {
		UUID actorId = UUID.randomUUID();
		Fixture fixture = musicianFixture(actorId, EventPerformerRequestStatus.REJECTED);
		fixture.event().setPerformerApprovalStatus(EventPerformerApprovalStatus.REJECTED);
		stubLocks(fixture);

		assertThatThrownBy(() -> service.accept(actorId, fixture.request().getId()))
				.isInstanceOf(SoundConnectException.class)
				.extracting("errorType")
				.isEqualTo(ErrorType.EVENT_PERFORMER_REQUEST_FINALIZED);
	}

	@Test
	void rejectKeepsSnapshotUnlinkedAndIsIdempotentOnRepeat() {
		UUID actorId = UUID.randomUUID();
		Fixture fixture = musicianFixture(actorId, EventPerformerRequestStatus.PENDING);
		stubLocks(fixture);

		var response = service.reject(actorId, fixture.request().getId());

		assertThat(response.status()).isEqualTo(EventPerformerRequestStatus.REJECTED);
		assertThat(fixture.event().getMusicianProfile()).isNull();
		assertThat(fixture.event().getBand()).isNull();
		assertThat(fixture.event().getManualPerformerName()).isEqualTo("bugrasahin");
		assertThat(fixture.event().getPerformerApprovalStatus())
				.isEqualTo(EventPerformerApprovalStatus.REJECTED);
		assertThat(fixture.event().isProfileCalendarApproved()).isFalse();

		var repeated = service.reject(actorId, fixture.request().getId());
		assertThat(repeated.status()).isEqualTo(EventPerformerRequestStatus.REJECTED);
	}

	@Test
	void unauthorizedRequestIdIsEnumerationSafeAndNeverLockedForMutation() {
		UUID ownerId = UUID.randomUUID();
		UUID attackerId = UUID.randomUUID();
		Fixture fixture = musicianFixture(ownerId, EventPerformerRequestStatus.PENDING);
		when(requestRepository.findAuthorizationSnapshotById(fixture.request().getId()))
				.thenReturn(Optional.of(authorizationSnapshot(fixture.request())));

		assertThatThrownBy(() -> service.accept(attackerId, fixture.request().getId()))
				.isInstanceOf(SoundConnectException.class)
				.extracting("errorType")
				.isEqualTo(ErrorType.EVENT_PERFORMER_REQUEST_NOT_FOUND);
		verify(eventRepository, never()).findByIdForUpdate(fixture.event().getId());
		verify(requestRepository, never()).findByIdForUpdate(fixture.request().getId());
	}

	@Test
	void bandDecisionUsesCentralFounderRepresentationPolicy() {
		UUID founderId = UUID.randomUUID();
		UUID requestId = UUID.randomUUID();
		Event event = event();
		Band band = new Band();
		band.setId(UUID.randomUUID());
		band.setName("Şahbaz");
		EventPerformerRequest request = EventPerformerRequest.builder()
				.event(event)
				.band(band)
				.performerNameSnapshot("Şahbaz")
				.status(EventPerformerRequestStatus.PENDING)
				.requestedByUserId(UUID.randomUUID())
				.build();
		request.setId(requestId);
		when(requestRepository.findAuthorizationSnapshotById(requestId))
				.thenReturn(Optional.of(authorizationSnapshot(request)));
		when(bandRepository.findByIdForUpdate(band.getId())).thenReturn(Optional.of(band));
		when(eventRepository.findByIdForUpdate(event.getId())).thenReturn(Optional.of(event));
		when(requestRepository.findByIdForUpdate(requestId)).thenReturn(Optional.of(request));
		when(bandRepresentationPolicy.canRepresent(founderId, band.getId())).thenReturn(true);

		service.accept(founderId, requestId);

		verify(bandRepresentationPolicy, times(2)).canRepresent(founderId, band.getId());
		InOrder lockOrder = inOrder(bandRepository, eventRepository, requestRepository);
		lockOrder.verify(bandRepository).findByIdForUpdate(band.getId());
		lockOrder.verify(eventRepository).findByIdForUpdate(event.getId());
		lockOrder.verify(requestRepository).findByIdForUpdate(requestId);
		assertThat(event.getBand()).isSameAs(band);
		assertThat(event.isProfileCalendarApproved()).isFalse();
	}

	@Test
	void createPendingBandRequestFailsClosedWhenNoActiveFounderCanDecide() {
		UUID venueOwnerId = UUID.randomUUID();
		Event event = event();
		Band band = new Band();
		band.setId(UUID.randomUUID());
		band.setName("Sahipsiz Grup");
		when(bandMemberRepository.findByBandId(band.getId())).thenReturn(List.of());

		assertThatThrownBy(() -> service.createPendingRequest(venueOwnerId, event, null, band))
				.isInstanceOf(SoundConnectException.class)
				.extracting("errorType")
				.isEqualTo(ErrorType.EVENT_PERFORMER_REQUEST_INVALID);

		verify(requestRepository, never()).save(any(EventPerformerRequest.class));
		verifyNoInteractions(notificationOutboxPublisher);
	}

	@Test
	void createPendingRequestRejectsMalformedGeneratedSnapshotBeforePersistence() {
		UUID venueOwnerId = UUID.randomUUID();
		for (String malformedName : List.of("   ", "x".repeat(101))) {
			User musicianOwner = new User();
			musicianOwner.setId(UUID.randomUUID());
			musicianOwner.setUsername(malformedName);
			MusicianProfile musician = new MusicianProfile();
			musician.setId(UUID.randomUUID());
			musician.setUser(musicianOwner);
			musician.setStageName(malformedName);

			assertThatThrownBy(() -> service.createPendingRequest(
					venueOwnerId,
					event(),
					musician,
					null
			))
					.isInstanceOf(SoundConnectException.class)
					.extracting("errorType")
					.isEqualTo(ErrorType.INVALID_PARAMETER);
		}

		verify(requestRepository, never()).save(any(EventPerformerRequest.class));
		verifyNoInteractions(notificationOutboxPublisher);
	}

	@Test
	void invalidateBandLocksEventBeforeRequestAndPreservesSnapshot() {
		Band band = new Band();
		band.setId(UUID.randomUUID());
		band.setName("Şahbaz");
		Event event = event();
		event.setBand(band);
		event.setManualPerformerName(null);
		event.setPerformerApprovalStatus(EventPerformerApprovalStatus.APPROVED);
		EventPerformerRequest request = EventPerformerRequest.builder()
				.event(event)
				.band(band)
				.performerNameSnapshot("Şahbaz")
				.status(EventPerformerRequestStatus.ACCEPTED)
				.requestedByUserId(UUID.randomUUID())
				.build();
		request.setId(UUID.randomUUID());
		when(bandRepository.findByIdForUpdate(band.getId())).thenReturn(Optional.of(band));
		when(requestRepository.findLockTargetsByBandId(band.getId())).thenReturn(List.of(
				new EventPerformerRequestLockTarget(request.getId(), event.getId())
		));
		when(eventRepository.findByIdForUpdate(event.getId())).thenReturn(Optional.of(event));
		when(requestRepository.findByIdForUpdate(request.getId())).thenReturn(Optional.of(request));

		service.invalidateForBand(band.getId());

		InOrder order = inOrder(bandRepository, eventRepository, requestRepository);
		order.verify(bandRepository).findByIdForUpdate(band.getId());
		order.verify(eventRepository).findByIdForUpdate(event.getId());
		order.verify(requestRepository).findByIdForUpdate(request.getId());
		assertThat(event.getBand()).isNull();
		assertThat(event.getManualPerformerName()).isEqualTo("Şahbaz");
		assertThat(event.getPerformerApprovalStatus()).isEqualTo(EventPerformerApprovalStatus.NOT_REQUIRED);
		verify(requestRepository).delete(request);
	}

	@Test
	void connectedRequestCarriesStableDedupeIdAndActionableProfileDisplayPayload() {
		UUID musicianOwnerId = UUID.randomUUID();
		Fixture fixture = musicianFixture(musicianOwnerId, EventPerformerRequestStatus.PENDING);
		fixture.event().setMusicianProfile(fixture.musician());
		fixture.event().setManualPerformerName(null);
		fixture.event().setPerformerApprovalStatus(EventPerformerApprovalStatus.APPROVED);
		when(requestRepository.save(any(EventPerformerRequest.class))).thenAnswer(invocation -> {
			EventPerformerRequest request = invocation.getArgument(0);
			request.setId(fixture.request().getId());
			return request;
		});

		service.createProfileVisibilityRequest(UUID.randomUUID(), fixture.event(), fixture.musician(), null);
		service.createProfileVisibilityRequest(UUID.randomUUID(), fixture.event(), fixture.musician(), null);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<NotificationInboundEvent>> captor = ArgumentCaptor.forClass(List.class);
		verify(notificationOutboxPublisher, times(2)).enqueueAll(captor.capture());
		NotificationInboundEvent first = captor.getAllValues().get(0).getFirst();
		NotificationInboundEvent second = captor.getAllValues().get(1).getFirst();
		assertThat(first.eventId()).isNotNull().isEqualTo(second.eventId());
		assertThat(first.recipientId()).isEqualTo(musicianOwnerId);
		assertThat(first.type()).isEqualTo(NotificationType.EVENT_PERFORMER_APPROVAL_REQUESTED);
		assertThat(first.payload())
				.containsEntry("requestPurpose", "PROFILE_VISIBILITY")
				.containsEntry("availableActions", List.of("ACCEPT", "REJECT"))
				.containsEntry("venueName", "SoundConnect Ankara")
				.containsEntry("eventTitle", "Gece Konseri")
				.containsEntry("performerName", "bugrasahin");
	}

	@Test
	void profileVisibilityAcceptKeepsConnectedPublicLinkAndIsIdempotent() {
		UUID actorId = UUID.randomUUID();
		Fixture fixture = connectedFixture(actorId);
		stubLocks(fixture);
		var response = service.accept(actorId, fixture.request().getId());
		assertThat(response.requestPurpose()).isEqualTo(EventPerformerRequestPurpose.PROFILE_VISIBILITY);
		assertThat(response.status()).isEqualTo(EventPerformerRequestStatus.ACCEPTED);
		assertThat(fixture.event().isProfileCalendarApproved()).isTrue();
		assertThat(fixture.event().getMusicianProfile()).isSameAs(fixture.musician());
		assertThat(fixture.event().getPerformerApprovalStatus()).isEqualTo(EventPerformerApprovalStatus.APPROVED);
		service.accept(actorId, fixture.request().getId());
		verify(eventRepository).save(fixture.event());
		verify(notificationOutboxPublisher).enqueueAll(any());
	}

	@Test
	void profileVisibilityRejectKeepsConnectedPublicLinkAndNeverPublishesOnCalendar() {
		UUID actorId = UUID.randomUUID();
		Fixture fixture = connectedFixture(actorId);
		stubLocks(fixture);
		var response = service.reject(actorId, fixture.request().getId());
		assertThat(response.status()).isEqualTo(EventPerformerRequestStatus.REJECTED);
		assertThat(fixture.event().isProfileCalendarApproved()).isFalse();
		assertThat(fixture.event().getMusicianProfile()).isSameAs(fixture.musician());
		assertThat(fixture.event().getManualPerformerName()).isNull();
		assertThat(fixture.event().getPerformerApprovalStatus()).isEqualTo(EventPerformerApprovalStatus.APPROVED);
		service.reject(actorId, fixture.request().getId());
		verify(eventRepository).save(fixture.event());
		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<NotificationInboundEvent>> notification = ArgumentCaptor.forClass(List.class);
		verify(notificationOutboxPublisher).enqueueAll(notification.capture());
		assertThat(notification.getValue()).singleElement().satisfies(value -> {
			assertThat(value.title()).isEqualTo("Profilde gösterim reddedildi");
			assertThat(value.message()).isEqualTo("bugrasahin etkinliğin profil takviminde gösterilmesini istemedi.");
			assertThat(value.payload()).containsEntry("requestPurpose", "PROFILE_VISIBILITY")
					.containsEntry("status", "REJECTED");
		});
		assertThatThrownBy(() -> service.accept(actorId, fixture.request().getId()))
				.isInstanceOf(SoundConnectException.class).extracting("errorType")
				.isEqualTo(ErrorType.EVENT_PERFORMER_REQUEST_FINALIZED);
	}

	@Test
	void profileVisibilityCannotAuthorizeAChangedPerformerOrUnlinkedEvent() {
		UUID actorId = UUID.randomUUID();
		Fixture fixture = connectedFixture(actorId);
		stubLocks(fixture);
		MusicianProfile unrelated = new MusicianProfile();
		unrelated.setId(UUID.randomUUID());
		fixture.event().setMusicianProfile(unrelated);
		assertThatThrownBy(() -> service.accept(actorId, fixture.request().getId()))
				.isInstanceOf(SoundConnectException.class).extracting("errorType")
				.isEqualTo(ErrorType.EVENT_PERFORMER_REQUEST_INVALID);
		assertThatThrownBy(() -> service.reject(actorId, fixture.request().getId()))
				.isInstanceOf(SoundConnectException.class).extracting("errorType")
				.isEqualTo(ErrorType.EVENT_PERFORMER_REQUEST_INVALID);
		fixture.event().setMusicianProfile(null);
		fixture.event().setPerformerApprovalStatus(EventPerformerApprovalStatus.PENDING);
		assertThatThrownBy(() -> service.accept(actorId, fixture.request().getId()))
				.isInstanceOf(SoundConnectException.class).extracting("errorType")
				.isEqualTo(ErrorType.EVENT_PERFORMER_REQUEST_INVALID);
		verify(eventRepository, never()).save(any());
		verifyNoInteractions(notificationOutboxPublisher);
	}

	@Test
	void visibilityDecisionRechecksBandFounderAfterLocking() {
		UUID actorId = UUID.randomUUID();
		Band band = new Band();
		band.setId(UUID.randomUUID());
		Event event = event();
		event.setBand(band);
		event.setManualPerformerName(null);
		event.setPerformerApprovalStatus(EventPerformerApprovalStatus.APPROVED);
		EventPerformerRequest request = EventPerformerRequest.builder().event(event).band(band)
				.performerNameSnapshot("Şahbaz").status(EventPerformerRequestStatus.PENDING)
				.requestPurpose(EventPerformerRequestPurpose.PROFILE_VISIBILITY).build();
		request.setId(UUID.randomUUID());
		when(requestRepository.findAuthorizationSnapshotById(request.getId()))
				.thenReturn(Optional.of(authorizationSnapshot(request)));
		when(bandRepresentationPolicy.canRepresent(actorId, band.getId())).thenReturn(true, false);
		when(bandRepository.findByIdForUpdate(band.getId())).thenReturn(Optional.of(band));
		when(eventRepository.findByIdForUpdate(event.getId())).thenReturn(Optional.of(event));
		when(requestRepository.findByIdForUpdate(request.getId())).thenReturn(Optional.of(request));
		assertThatThrownBy(() -> service.accept(actorId, request.getId()))
				.isInstanceOf(SoundConnectException.class).extracting("errorType")
				.isEqualTo(ErrorType.EVENT_PERFORMER_REQUEST_NOT_FOUND);
		assertThat(event.isProfileCalendarApproved()).isFalse();
		verify(eventRepository, never()).save(any());
	}

	private Fixture connectedFixture(UUID actorId) {
		Fixture fixture = musicianFixture(actorId, EventPerformerRequestStatus.PENDING);
		fixture.request().setRequestPurpose(EventPerformerRequestPurpose.PROFILE_VISIBILITY);
		fixture.event().setMusicianProfile(fixture.musician());
		fixture.event().setManualPerformerName(null);
		fixture.event().setPerformerApprovalStatus(EventPerformerApprovalStatus.APPROVED);
		return fixture;
	}

	@Test
	void connectedBandAcceptAndRejectOnlyControlCalendarPermission() {
		for (boolean accepted : List.of(true, false)) {
			UUID actorId = UUID.randomUUID();
			Band band = new Band();
			band.setId(UUID.randomUUID());
			band.setName("Şahbaz");
			Event event = event();
			event.setBand(band);
			event.setManualPerformerName(null);
			event.setPerformerApprovalStatus(EventPerformerApprovalStatus.APPROVED);
			EventPerformerRequest request = EventPerformerRequest.builder().event(event).band(band)
					.performerNameSnapshot("Şahbaz").status(EventPerformerRequestStatus.PENDING)
					.requestPurpose(EventPerformerRequestPurpose.PROFILE_VISIBILITY).build();
			request.setId(UUID.randomUUID());
			when(requestRepository.findAuthorizationSnapshotById(request.getId()))
					.thenReturn(Optional.of(authorizationSnapshot(request)));
			when(bandRepresentationPolicy.canRepresent(actorId, band.getId())).thenReturn(true);
			when(bandRepository.findByIdForUpdate(band.getId())).thenReturn(Optional.of(band));
			when(eventRepository.findByIdForUpdate(event.getId())).thenReturn(Optional.of(event));
			when(requestRepository.findByIdForUpdate(request.getId())).thenReturn(Optional.of(request));
			var response = accepted ? service.accept(actorId, request.getId()) : service.reject(actorId, request.getId());
			assertThat(response.status()).isEqualTo(accepted ? EventPerformerRequestStatus.ACCEPTED : EventPerformerRequestStatus.REJECTED);
			assertThat(event.isProfileCalendarApproved()).isEqualTo(accepted);
			assertThat(event.getBand()).isSameAs(band);
			assertThat(event.getMusicianProfile()).isNull();
			assertThat(event.getManualPerformerName()).isNull();
			assertThat(event.getPerformerApprovalStatus()).isEqualTo(EventPerformerApprovalStatus.APPROVED);
			verify(bandRepresentationPolicy, times(2)).canRepresent(actorId, band.getId());
		}
	}

	@Test
	void connectedCreationCannotAskForADifferentProfileThanItsPublicLink() {
		UUID actorId = UUID.randomUUID();
		Fixture fixture = connectedFixture(actorId);
		MusicianProfile other = new MusicianProfile();
		other.setId(UUID.randomUUID());
		assertThatThrownBy(() -> service.createProfileVisibilityRequest(
				UUID.randomUUID(), fixture.event(), other, null))
				.isInstanceOf(SoundConnectException.class).extracting("errorType")
				.isEqualTo(ErrorType.EVENT_PERFORMER_REQUEST_INVALID);
		verifyNoInteractions(requestRepository, notificationOutboxPublisher);
	}

	@Test
	void getMineScopesMusicianRequestsToActorOwnedProfileAndPreservesPage() {
		UUID actorId = UUID.randomUUID();
		UUID musicianProfileId = UUID.randomUUID();
		Pageable expectedPage = org.springframework.data.domain.PageRequest.of(3, 15);
		when(requestRepository.findMineForMusician(
				actorId,
				musicianProfileId,
				EventPerformerRequestStatus.PENDING,
				expectedPage
		)).thenReturn(Page.empty(expectedPage));

		var response = service.getMine(
				actorId,
				EventPerformerRequestStatus.PENDING,
				PerformerType.MUSICIAN,
				musicianProfileId,
				3,
				15
		);

		assertThat(response.content()).isEmpty();
		assertThat(response.page()).isEqualTo(3);
		assertThat(response.size()).isEqualTo(15);
		verify(requestRepository).findMineForMusician(
				actorId,
				musicianProfileId,
				EventPerformerRequestStatus.PENDING,
				expectedPage
		);
	}

	@Test
	void authorizedInboxResolvesEventPosterWithoutConfusingVenueAvatar() {
		UUID actorId = UUID.randomUUID();
		Fixture fixture = musicianFixture(actorId, EventPerformerRequestStatus.PENDING);
		UUID posterId = UUID.randomUUID();
		fixture.event().setPosterImage(posterId.toString());
		when(mediaAssetService.getDisplayUrl(posterId)).thenReturn("https://cdn.test/event-poster.jpg");
		var pageable = org.springframework.data.domain.PageRequest.of(0, 20);
		when(requestRepository.findMineForMusician(actorId, fixture.musician().getId(),
				EventPerformerRequestStatus.PENDING, pageable))
				.thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(fixture.request())));

		var response = service.getMine(actorId, EventPerformerRequestStatus.PENDING,
				PerformerType.MUSICIAN, fixture.musician().getId(), 0, 20);

		assertThat(response.content()).hasSize(1);
		assertThat(response.content().getFirst().posterImage()).isEqualTo("https://cdn.test/event-poster.jpg");
		assertThat(response.content().getFirst().venueProfilePictureUrl()).isNull();
	}

	@Test
	void unavailablePosterDoesNotBlockAuthorizedAcceptance() {
		UUID actorId = UUID.randomUUID();
		Fixture fixture = musicianFixture(actorId, EventPerformerRequestStatus.PENDING);
		UUID posterId = UUID.randomUUID();
		fixture.event().setPosterImage(posterId.toString());
		when(mediaAssetService.getDisplayUrl(posterId)).thenThrow(new IllegalStateException("Unavailable"));
		stubLocks(fixture);

		var response = service.accept(actorId, fixture.request().getId());

		assertThat(response.posterImage()).isNull();
		assertThat(response.status()).isEqualTo(EventPerformerRequestStatus.ACCEPTED);
		assertThat(fixture.event().isProfileCalendarApproved()).isFalse();
		assertThat(response.profileCalendarApproved()).isFalse();
	}

	@Test
	void getMineScopesBandRequestsToActiveFounderAndAllowsNullStatus() {
		UUID actorId = UUID.randomUUID();
		UUID bandId = UUID.randomUUID();
		Pageable expectedPage = org.springframework.data.domain.PageRequest.of(0, 20);
		when(requestRepository.findMineForBand(
				actorId,
				bandId,
				null,
				com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandMemberShipStatus.ACTIVE,
				com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandRole.FOUNDER,
				expectedPage
		)).thenReturn(Page.empty(expectedPage));

		var response = service.getMine(actorId, null, PerformerType.BAND, bandId, 0, 20);

		assertThat(response.content()).isEmpty();
		verify(requestRepository).findMineForBand(
				actorId,
				bandId,
				null,
				com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandMemberShipStatus.ACTIVE,
				com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandRole.FOUNDER,
				expectedPage
		);
	}

	@Test
	void getMineRejectsUnpairedAndManualTargetScopesWithoutQuerying() {
		UUID actorId = UUID.randomUUID();
		UUID targetId = UUID.randomUUID();

		assertThatThrownBy(() -> service.getMine(
				actorId, null, PerformerType.MUSICIAN, null, 0, 20
		))
				.isInstanceOf(SoundConnectException.class)
				.extracting("errorType")
				.isEqualTo(ErrorType.INVALID_PARAMETER);
		assertThatThrownBy(() -> service.getMine(
				actorId, null, null, targetId, 0, 20
		))
				.isInstanceOf(SoundConnectException.class)
				.extracting("errorType")
				.isEqualTo(ErrorType.INVALID_PARAMETER);
		assertThatThrownBy(() -> service.getMine(
				actorId, null, PerformerType.MANUAL, targetId, 0, 20
		))
				.isInstanceOf(SoundConnectException.class)
				.extracting("errorType")
				.isEqualTo(ErrorType.INVALID_PARAMETER);

		verifyNoInteractions(requestRepository);
	}

	private void stubLocks(Fixture fixture) {
		when(requestRepository.findAuthorizationSnapshotById(fixture.request().getId()))
				.thenReturn(Optional.of(authorizationSnapshot(fixture.request())));
		when(eventRepository.findByIdForUpdate(fixture.event().getId())).thenReturn(Optional.of(fixture.event()));
		when(requestRepository.findByIdForUpdate(fixture.request().getId())).thenReturn(Optional.of(fixture.request()));
	}

	private EventPerformerRequestAuthorizationSnapshot authorizationSnapshot(EventPerformerRequest request) {
		return new EventPerformerRequestAuthorizationSnapshot(
				request.getEvent().getId(),
				request.getMusicianProfile() == null || request.getMusicianProfile().getUser() == null
						? null
						: request.getMusicianProfile().getUser().getId(),
				request.getBand() == null ? null : request.getBand().getId()
		);
	}

	private Fixture musicianFixture(UUID ownerId, EventPerformerRequestStatus status) {
		User musicianUser = new User();
		musicianUser.setId(ownerId);
		musicianUser.setUsername("bugrasahin");
		MusicianProfile musician = new MusicianProfile();
		musician.setId(UUID.randomUUID());
		musician.setUser(musicianUser);
		Event event = event();
		EventPerformerRequest request = EventPerformerRequest.builder()
				.event(event)
				.musicianProfile(musician)
				.performerNameSnapshot("bugrasahin")
				.status(status)
				.requestedByUserId(UUID.randomUUID())
				.build();
		request.setId(UUID.randomUUID());
		return new Fixture(event, request, musician);
	}

	private Event event() {
		User venueOwner = new User();
		venueOwner.setId(UUID.randomUUID());
		Venue venue = new Venue();
		venue.setId(UUID.randomUUID());
		venue.setName("SoundConnect Ankara");
		venue.setOwner(venueOwner);
		Event event = Event.builder()
				.title("Gece Konseri")
				.eventDate(LocalDate.of(2026, 9, 12))
				.startTime(LocalTime.of(21, 0))
				.venue(venue)
				.manualPerformerName("bugrasahin")
				.performerApprovalStatus(EventPerformerApprovalStatus.PENDING)
				.build();
		event.setId(UUID.randomUUID());
		return event;
	}

	private record Fixture(Event event, EventPerformerRequest request, MusicianProfile musician) {
	}
}
