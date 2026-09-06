package com.berkayb.soundconnect.modules.event.performer.service;

import com.berkayb.soundconnect.modules.event.entity.Event;
import com.berkayb.soundconnect.modules.event.support.EventPosterResolver;
import com.berkayb.soundconnect.modules.event.support.EventScheduleClock;
import com.berkayb.soundconnect.modules.event.enums.EventPerformerApprovalStatus;
import com.berkayb.soundconnect.modules.event.enums.EventOrigin;
import com.berkayb.soundconnect.modules.event.enums.PerformerType;
import com.berkayb.soundconnect.modules.event.performer.dto.EventPerformerRequestResponseDto;
import com.berkayb.soundconnect.modules.event.performer.dto.EventPerformerRequestAuthorizationSnapshot;
import com.berkayb.soundconnect.modules.event.performer.dto.EventPerformerRequestLockTarget;
import com.berkayb.soundconnect.modules.event.performer.entity.EventPerformerRequest;
import com.berkayb.soundconnect.modules.event.performer.enums.EventPerformerRequestStatus;
import com.berkayb.soundconnect.modules.event.performer.enums.EventPerformerRequestPurpose;
import com.berkayb.soundconnect.modules.event.performer.outbox.EventPerformerNotificationOutboxPublisher;
import com.berkayb.soundconnect.modules.event.performer.repository.EventPerformerRequestRepository;
import com.berkayb.soundconnect.modules.event.repository.EventRepository;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.Band;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.BandMember;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandMemberShipStatus;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandRole;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository.BandMemberRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository.BandRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.support.BandRepresentationPolicy;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.modules.profile.VenueProfile.repository.VenueProfileRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationInboundEvent;
import com.berkayb.soundconnect.shared.response.PageResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class EventPerformerRequestServiceImpl implements EventPerformerRequestService {

	private static final int MAX_PAGE_SIZE = 50;

	private final EventPerformerRequestRepository requestRepository;
	private final EventRepository eventRepository;
	private final BandMemberRepository bandMemberRepository;
	private final BandRepository bandRepository;
	private final BandRepresentationPolicy bandRepresentationPolicy;
	private final EventPerformerNotificationOutboxPublisher notificationOutboxPublisher;
	private final VenueProfileRepository venueProfileRepository;
	private final MediaAssetService mediaAssetService;
	private final EventScheduleClock scheduleClock;

	@Override
	@Transactional
	public void createPendingRequest(
			UUID venueOwnerUserId,
			Event event,
			MusicianProfile musician,
			Band band
	) {
		createRequest(venueOwnerUserId, event, musician, band, EventPerformerRequestPurpose.PERFORMER_CONSENT);
	}

	@Override
	@Transactional
	public void createProfileVisibilityRequest(
			UUID venueOwnerUserId, Event event, MusicianProfile musician, Band band
	) {
		createRequest(venueOwnerUserId, event, musician, band, EventPerformerRequestPurpose.PROFILE_VISIBILITY);
	}

	private void createRequest(
			UUID venueOwnerUserId, Event event, MusicianProfile musician, Band band,
			EventPerformerRequestPurpose purpose
	) {
		if (event == null || event.getId() == null || event.getEventOrigin() != EventOrigin.VENUE
				|| event.getVenue() == null || venueOwnerUserId == null || (musician == null) == (band == null)) {
			throw new SoundConnectException(ErrorType.EVENT_PERFORMER_REQUEST_INVALID);
		}
		if (purpose == EventPerformerRequestPurpose.PROFILE_VISIBILITY) {
			assertLinkedTarget(event, musician, band);
		}
		if (event.isProfileCalendarApproved()) {
			throw new SoundConnectException(ErrorType.EVENT_PERFORMER_REQUEST_INVALID);
		}

		List<UUID> recipients = pendingRecipientIds(musician, band);
		if (recipients.isEmpty()) {
			// A pending request without an authorized decider can never establish
			// consent. Fail the surrounding event transaction closed instead of
			// leaving an unowned request and a permanently pending public snapshot.
			throw new SoundConnectException(ErrorType.EVENT_PERFORMER_REQUEST_INVALID);
		}

		String performerName = pendingPerformerName(musician, band);
		EventPerformerRequest request = EventPerformerRequest.builder()
				.event(event)
				.musicianProfile(musician)
				.band(band)
				.performerNameSnapshot(performerName)
				.status(EventPerformerRequestStatus.PENDING)
				.requestPurpose(purpose)
				.requestedByUserId(venueOwnerUserId)
				.build();
		EventPerformerRequest saved = requestRepository.save(request);

		boolean profileOnly = purpose == EventPerformerRequestPurpose.PROFILE_VISIBILITY;
		notificationOutboxPublisher.enqueueAll(buildPerformerNotifications(
				saved,
				recipients,
				NotificationType.EVENT_PERFORMER_APPROVAL_REQUESTED,
				profileOnly
						? (band == null ? "Profilinde gösterilsin mi?" : "Grubunuzun profilinde gösterilsin mi?")
						: (band == null ? "Etkinlik katılım onayı" : "Grubunuz için etkinlik katılım onayı"),
				profileOnly
						? venueName(event) + ", “" + safe(event.getTitle(), "Etkinlik") + "” etkinliğine "
								+ (band == null ? "seni" : "“" + performerName + "” adlı grubunuzu")
								+ " ekledi. Etkinliğin " + (band == null ? "profilindeki" : "grubunuzun profilindeki")
								+ " takvimde de gösterilmesini onaylıyor musun?"
						: venueName(event) + ", “" + safe(event.getTitle(), "Etkinlik") + "” etkinliğine "
								+ (band == null ? "seni" : "“" + performerName + "” adlı grubunuzu") + " eklemek istiyor.",
				"APPROVAL_REQUESTED",
				true
		));
	}

	@Override
	@Transactional(readOnly = true)
	public PageResponse<EventPerformerRequestResponseDto> getMine(
			UUID actorUserId,
			EventPerformerRequestStatus status,
			PerformerType targetType,
			UUID targetId,
			int page,
			int size
	) {
		boolean targetTypeProvided = targetType != null;
		boolean targetIdProvided = targetId != null;
		if (actorUserId == null
				|| page < 0
				|| page > 100
				|| size < 1
				|| size > MAX_PAGE_SIZE
				|| targetTypeProvided != targetIdProvided
				|| targetType == PerformerType.MANUAL) {
			throw new SoundConnectException(ErrorType.INVALID_PARAMETER);
		}
		PageRequest pageable = PageRequest.of(page, size, Sort.unsorted());
		var requests = switch (targetType) {
			case MUSICIAN -> requestRepository.findMineForMusician(actorUserId, targetId, status, pageable);
			case BAND -> requestRepository.findMineForBand(
					actorUserId,
					targetId,
					status,
					BandMemberShipStatus.ACTIVE,
					BandRole.FOUNDER,
					pageable
			);
			case null -> status == null
					? requestRepository.findMine(
							actorUserId,
							BandMemberShipStatus.ACTIVE,
							BandRole.FOUNDER,
							pageable
					)
					: requestRepository.findMineByStatus(
							actorUserId,
							status,
							BandMemberShipStatus.ACTIVE,
							BandRole.FOUNDER,
							pageable
					);
			case MANUAL -> throw new SoundConnectException(ErrorType.INVALID_PARAMETER);
		};
		return PageResponse.from(requests.map(this::toDto));
	}

	@Override
	@Transactional
	public EventPerformerRequestResponseDto accept(UUID actorUserId, UUID requestId) {
		return accept(actorUserId, requestId, null);
	}

	@Override
	@Transactional
	public EventPerformerRequestResponseDto accept(UUID actorUserId, UUID requestId, Boolean showOnProfile) {
		EventPerformerRequest request = lockAuthorizedRequest(actorUserId, requestId);
		return acceptLocked(actorUserId, request, showOnProfile, false);
	}

	@Override
	@Transactional
	public EventPerformerRequestResponseDto reconsider(UUID actorUserId, UUID requestId, Boolean showOnProfile) {
		if (showOnProfile == null) throw new SoundConnectException(ErrorType.INVALID_PARAMETER);
		EventPerformerRequest request = lockAuthorizedRequest(actorUserId, requestId);
		return acceptLocked(actorUserId, request, showOnProfile, true);
	}

	private EventPerformerRequestResponseDto acceptLocked(
			UUID actorUserId, EventPerformerRequest request, Boolean showOnProfile, boolean reconsider) {
		Event event = request.getEvent();
		boolean profileOnly = request.getRequestPurpose() == EventPerformerRequestPurpose.PROFILE_VISIBILITY;
		// A legacy bodyless participation acceptance establishes only the public
		// performer link. A visibility-only invitation explicitly asks to publish,
		// so accepting that purpose retains its existing opt-in semantics.
		boolean publishOnProfile = showOnProfile == null ? profileOnly : showOnProfile;
		if (request.getStatus() == EventPerformerRequestStatus.ACCEPTED) {
			if (!Objects.equals(request.getAcceptedProfilePublication(), publishOnProfile)) {
				throw new SoundConnectException(ErrorType.EVENT_PERFORMER_REQUEST_FINALIZED);
			}
			return toDto(request);
		}
		if (request.getStatus() != (reconsider
				? EventPerformerRequestStatus.REJECTED : EventPerformerRequestStatus.PENDING)) {
			throw new SoundConnectException(ErrorType.EVENT_PERFORMER_REQUEST_FINALIZED);
		}
		assertBeforeStart(event);

		if (profileOnly && !publishOnProfile) {
			// Declining a visibility-only invitation uses reject; it must not create
			// an accepted request that contradicts its sole consent purpose.
			throw new SoundConnectException(ErrorType.INVALID_PARAMETER);
		}
		if (reconsider) assertRejectedRequestEvent(request);
		else assertPendingRequestEvent(request);
		if (request.getRequestPurpose() == EventPerformerRequestPurpose.PERFORMER_CONSENT) {
			if (request.targetsBand()) {
				event.setBand(request.getBand());
				event.setMusicianProfile(null);
			} else {
				event.setMusicianProfile(request.getMusicianProfile());
				event.setBand(null);
			}
			event.setManualPerformerName(null);
			event.setPerformerApprovalStatus(EventPerformerApprovalStatus.APPROVED);
		}
		event.setProfileCalendarApproved(publishOnProfile);
		event.setProfilePublicationVersion(event.getProfilePublicationVersion() + 1);
		request.setAcceptedProfilePublication(publishOnProfile);
		request.setStatus(EventPerformerRequestStatus.ACCEPTED);
		request.setDecidedByUserId(actorUserId);
		request.setDecidedAt(scheduleClock.localNow());
		eventRepository.save(event);
		requestRepository.save(request);

		publishVenueDecisionNotification(request, true);
		return toDto(request);
	}

	@Override
	@Transactional
	public EventPerformerRequestResponseDto reject(UUID actorUserId, UUID requestId) {
		EventPerformerRequest request = lockAuthorizedRequest(actorUserId, requestId);
		if (request.getStatus() == EventPerformerRequestStatus.REJECTED) {
			return toDto(request);
		}
		if (request.getStatus() == EventPerformerRequestStatus.ACCEPTED) {
			throw new SoundConnectException(ErrorType.EVENT_PERFORMER_REQUEST_FINALIZED);
		}

		Event event = request.getEvent();
		assertBeforeStart(event);
		assertPendingRequestEvent(request);
		if (request.getRequestPurpose() == EventPerformerRequestPurpose.PERFORMER_CONSENT) {
			event.setManualPerformerName(request.getPerformerNameSnapshot());
			event.setPerformerApprovalStatus(EventPerformerApprovalStatus.REJECTED);
		}
		event.setProfileCalendarApproved(false);
		request.setStatus(EventPerformerRequestStatus.REJECTED);
		request.setDecidedByUserId(actorUserId);
		request.setDecidedAt(scheduleClock.localNow());
		eventRepository.save(event);
		requestRepository.save(request);

		publishVenueDecisionNotification(request, false);
		return toDto(request);
	}

	@Override
	@Transactional
	public void deleteForEvent(UUID eventId) {
		if (eventId != null) {
			requestRepository.deleteAllByEvent_Id(eventId);
		}
	}

	@Override
	@Transactional
	public void invalidateForBand(UUID bandId) {
		if (bandId == null) return;
		if (bandRepository.findByIdForUpdate(bandId).isEmpty()) return;
		List<EventPerformerRequestLockTarget> targets = requestRepository.findLockTargetsByBandId(bandId);
		for (EventPerformerRequestLockTarget target : targets) {
			// Event deletion uses this same order. If it won the race, the event and
			// cascading request are already gone and there is nothing left to repair.
			Event event = eventRepository.findByIdForUpdate(target.eventId()).orElse(null);
			if (event == null) continue;
			EventPerformerRequest request = requestRepository.findByIdForUpdate(target.requestId()).orElse(null);
			if (request == null || request.getBand() == null
					|| !bandId.equals(request.getBand().getId())) {
				continue;
			}
			if (!hasText(event.getManualPerformerName())) {
				event.setManualPerformerName(request.getPerformerNameSnapshot());
			}
			event.setBand(null);
			event.setPerformerApprovalStatus(EventPerformerApprovalStatus.NOT_REQUIRED);
			event.setProfileCalendarApproved(false);
			requestRepository.delete(request);
		}
	}

	private EventPerformerRequest lockAuthorizedRequest(UUID actorUserId, UUID requestId) {
		if (actorUserId == null || requestId == null) {
			throw requestNotFound();
		}

		// Authorize from an immutable projection rather than loading the request
		// entity into the persistence context before it is locked. Otherwise a
		// concurrent decision can leave this transaction with stale status/version
		// state even after findByIdForUpdate returns.
		EventPerformerRequestAuthorizationSnapshot preflight = requestRepository
				.findAuthorizationSnapshotById(requestId)
				.orElseThrow(this::requestNotFound);
		assertCanRepresent(actorUserId, preflight);
		UUID eventId = preflight.eventId();
		if (eventId == null) {
			throw requestNotFound();
		}
		if (preflight.bandId() != null) {
			bandRepository.findByIdForUpdate(preflight.bandId()).orElseThrow(this::requestNotFound);
		}
		eventRepository.findByIdForUpdate(eventId).orElseThrow(this::requestNotFound);
		EventPerformerRequest locked = requestRepository.findByIdForUpdate(requestId)
				.orElseThrow(this::requestNotFound);
		assertCanRepresent(actorUserId, locked);
		if (locked.getEvent() == null || locked.getEvent().getEventOrigin() != EventOrigin.VENUE
				|| locked.getEvent().getVenue() == null) {
			throw new SoundConnectException(ErrorType.EVENT_PERFORMER_REQUEST_INVALID);
		}
		return locked;
	}

	private void assertBeforeStart(Event event) {
		Instant startsAt = scheduleClock.startsAt(event);
		if (startsAt == null || !scheduleClock.instant().isBefore(startsAt)) {
			throw new SoundConnectException(ErrorType.EVENT_PERFORMER_REQUEST_EXPIRED);
		}
	}

	private void assertRejectedRequestEvent(EventPerformerRequest request) {
		Event event = request.getEvent();
		if (request.getRequestPurpose() == EventPerformerRequestPurpose.PROFILE_VISIBILITY) {
			assertLinkedTarget(event, request.getMusicianProfile(), request.getBand());
		} else if (request.getRequestPurpose() != EventPerformerRequestPurpose.PERFORMER_CONSENT
				|| event.getPerformerApprovalStatus() != EventPerformerApprovalStatus.REJECTED
				|| event.getMusicianProfile() != null || event.getBand() != null
				|| event.isProfileCalendarApproved()) {
			throw new SoundConnectException(ErrorType.EVENT_PERFORMER_REQUEST_INVALID);
		}
	}

	private void assertPendingRequestEvent(EventPerformerRequest request) {
		Event event = request.getEvent();
		if (event.isProfileCalendarApproved()) {
			throw new SoundConnectException(ErrorType.EVENT_PERFORMER_REQUEST_INVALID);
		}
		if (request.getRequestPurpose() == EventPerformerRequestPurpose.PROFILE_VISIBILITY) {
			assertLinkedTarget(event, request.getMusicianProfile(), request.getBand());
		} else if (request.getRequestPurpose() != EventPerformerRequestPurpose.PERFORMER_CONSENT
				|| event.getPerformerApprovalStatus() != EventPerformerApprovalStatus.PENDING
				|| event.getMusicianProfile() != null || event.getBand() != null) {
			throw new SoundConnectException(ErrorType.EVENT_PERFORMER_REQUEST_INVALID);
		}
	}

	private void assertLinkedTarget(Event event, MusicianProfile musician, Band band) {
		boolean sameMusician = musician != null && musician.getId() != null
				&& event.getMusicianProfile() != null && event.getBand() == null
				&& musician.getId().equals(event.getMusicianProfile().getId());
		boolean sameBand = band != null && band.getId() != null
				&& event.getBand() != null && event.getMusicianProfile() == null
				&& band.getId().equals(event.getBand().getId());
		if (event.getPerformerApprovalStatus() != EventPerformerApprovalStatus.APPROVED
				|| event.getManualPerformerName() != null || sameMusician == sameBand) {
			throw new SoundConnectException(ErrorType.EVENT_PERFORMER_REQUEST_INVALID);
		}
	}

	private void assertCanRepresent(
			UUID actorUserId,
			EventPerformerRequestAuthorizationSnapshot request
	) {
		boolean musicianTarget = request.musicianOwnerUserId() != null;
		boolean bandTarget = request.bandId() != null;
		boolean authorized = musicianTarget != bandTarget
				&& (bandTarget
						? bandRepresentationPolicy.canRepresent(actorUserId, request.bandId())
						: actorUserId.equals(request.musicianOwnerUserId()));
		if (!authorized) {
			throw requestNotFound();
		}
	}

	private void assertCanRepresent(UUID actorUserId, EventPerformerRequest request) {
		boolean musicianTarget = request.getMusicianProfile() != null;
		boolean bandTarget = request.getBand() != null;
		boolean authorized = musicianTarget != bandTarget;
		if (authorized && bandTarget) {
			UUID bandId = request.getBand() == null ? null : request.getBand().getId();
			authorized = bandRepresentationPolicy.canRepresent(actorUserId, bandId);
		} else if (authorized) {
			authorized = request.getMusicianProfile() != null
					&& request.getMusicianProfile().getUser() != null
					&& actorUserId.equals(request.getMusicianProfile().getUser().getId());
		}
		if (!authorized) {
			// Deliberately indistinguishable from an unknown id.
			throw requestNotFound();
		}
	}

	private SoundConnectException requestNotFound() {
		return new SoundConnectException(ErrorType.EVENT_PERFORMER_REQUEST_NOT_FOUND);
	}

	private EventPerformerRequestResponseDto toDto(EventPerformerRequest request) {
		Event event = request.getEvent();
		Instant now = scheduleClock.instant();
		Instant startsAt = scheduleClock.startsAt(event);
		boolean beforeStart = startsAt != null && now.isBefore(startsAt);
		boolean pending = request.getStatus() == EventPerformerRequestStatus.PENDING;
		boolean rejected = request.getStatus() == EventPerformerRequestStatus.REJECTED;
		return new EventPerformerRequestResponseDto(
				request.getId(),
				event.getId(),
				request.getMusicianProfile() == null ? null : request.getMusicianProfile().getId(),
				request.getBand() == null ? null : request.getBand().getId(),
				request.targetsBand() ? PerformerType.BAND : PerformerType.MUSICIAN,
				request.getPerformerNameSnapshot(),
				event.getVenue().getId(),
				event.getVenue().getName(),
				venueProfilePictureUrl(event),
				event.getTitle(),
				event.getEventDate(),
				event.getStartTime(),
				event.getEndTime(),
				request.getStatus(),
				request.getRequestPurpose(),
				request.getCreatedAt(),
				request.getDecidedAt(),
				EventPosterResolver.resolve(event.getPosterImage(), mediaAssetService),
				event.isProfileCalendarApproved(),
				pending && beforeStart,
				rejected && beforeStart,
				(pending || rejected) && !beforeStart,
				now,
				startsAt
		);
	}

	private void publishVenueDecisionNotification(EventPerformerRequest request, boolean accepted) {
		Event event = request.getEvent();
		UUID venueOwnerId = event.getVenue() == null || event.getVenue().getOwner() == null
				? null
				: event.getVenue().getOwner().getId();
		if (venueOwnerId == null) return;
		NotificationType type = accepted
				? NotificationType.EVENT_PERFORMER_APPROVED
				: NotificationType.EVENT_PERFORMER_REJECTED;
		String action = accepted ? "APPROVED" : "REJECTED";
		String title = accepted ? "Etkinlik katılımı onaylandı" : "Etkinlik katılımı reddedildi";
		String message = request.getPerformerNameSnapshot() + (accepted
				? " etkinliğe katılımını onayladı."
				: " etkinliğe katılımını reddetti.");
		if (request.getRequestPurpose() == EventPerformerRequestPurpose.PROFILE_VISIBILITY) {
			title = accepted ? "Profilde gösterim onaylandı" : "Profilde gösterim reddedildi";
			message = request.getPerformerNameSnapshot() + (accepted
					? " etkinliğin profil takviminde gösterilmesini onayladı."
					: " etkinliğin profil takviminde gösterilmesini istemedi.");
		}
		notificationOutboxPublisher.enqueueAll(buildPerformerNotifications(
				request,
				List.of(venueOwnerId),
				type,
				title,
				message,
				action,
				false
		));
	}

	private List<UUID> pendingRecipientIds(MusicianProfile musician, Band band) {
		if (musician != null && musician.getUser() != null && musician.getUser().getId() != null) {
			return List.of(musician.getUser().getId());
		}
		return activeBandMemberIds(band, true);
	}

	private List<UUID> activeBandMemberIds(Band band, boolean foundersOnly) {
		if (band == null || band.getId() == null) return List.of();
		return bandMemberRepository.findByBandId(band.getId()).stream()
				.filter(member -> member.getStatus() == BandMemberShipStatus.ACTIVE)
				.filter(member -> !foundersOnly || member.getBandRole() == BandRole.FOUNDER)
				.map(BandMember::getUser)
				.filter(Objects::nonNull)
				.map(user -> user.getId())
				.filter(Objects::nonNull)
				.distinct()
				.toList();
	}

	private List<NotificationInboundEvent> buildPerformerNotifications(
			EventPerformerRequest request,
			List<UUID> recipients,
			NotificationType type,
			String title,
			String message,
			String action,
			boolean actionable
	) {
		return buildPerformerNotifications(
				request,
				recipients,
				type,
				title,
				message,
				action,
				actionable,
				request.getEvent(),
				request.getMusicianProfile(),
				request.getBand()
		);
	}

	private List<NotificationInboundEvent> buildPerformerNotifications(
			EventPerformerRequest request,
			List<UUID> recipients,
			NotificationType type,
			String title,
			String message,
			String action,
			boolean actionable,
			Event event,
			MusicianProfile musician,
			Band band
	) {
		Map<String, Object> payload = new HashMap<>();
		payload.put("module", "EVENT_PERFORMER");
		payload.put("action", action);
		payload.put("eventId", event.getId().toString());
		payload.put("venueId", event.getVenue().getId().toString());
		payload.put("venueName", venueName(event));
		payload.put("eventTitle", safe(event.getTitle(), "Etkinlik"));
		payload.put("performerName", request == null
				? displayName(musician, band)
				: request.getPerformerNameSnapshot());
		String venueAvatarUrl = venueProfilePictureUrl(event);
		if (hasText(venueAvatarUrl)) {
			payload.put("venueProfilePictureUrl", venueAvatarUrl);
			payload.put("venueAvatarUrl", venueAvatarUrl);
			payload.put("actorAvatarUrl", venueAvatarUrl);
		}
		payload.put("performerType", band == null ? PerformerType.MUSICIAN.name() : PerformerType.BAND.name());
		if (musician != null) payload.put("musicianProfileId", musician.getId().toString());
		if (band != null) payload.put("bandId", band.getId().toString());
		if (request != null) {
			payload.put("requestId", request.getId().toString());
			payload.put("status", request.getStatus().name());
			payload.put("requestPurpose", request.getRequestPurpose().name());
		}
		if (actionable) {
			payload.put("availableActions", List.of("ACCEPT", "REJECT"));
		}

		List<NotificationInboundEvent> notifications = new ArrayList<>();
		for (UUID recipient : recipients.stream().filter(Objects::nonNull).distinct().toList()) {
			String requestKey = request == null ? "none" : request.getId().toString();
			UUID notificationEventId = UUID.nameUUIDFromBytes((
					"EVENT_PERFORMER|" + action + "|" + event.getId() + "|" + requestKey + "|" + recipient
			).getBytes(StandardCharsets.UTF_8));
			notifications.add(NotificationInboundEvent.builder()
					.eventId(notificationEventId)
					.recipientId(recipient)
					.type(type)
					.title(title)
					.message(message)
					.payload(Map.copyOf(payload))
					.emailForce(false)
					.occurredAt(Instant.now())
					.build());
		}
		return List.copyOf(notifications);
	}

	private String displayName(MusicianProfile musician, Band band) {
		if (band != null) return safe(band.getName(), "Grup");
		if (musician != null && musician.getUser() != null) {
			String username = musician.getUser().getUsername();
			if (hasText(username)) return username.trim();
		}
		if (musician != null) return safe(musician.getStageName(), "Sanatçı");
		return "Sanatçı";
	}

	private String pendingPerformerName(MusicianProfile musician, Band band) {
		String raw = null;
		if (band != null) {
			raw = band.getName();
		} else if (musician != null && musician.getUser() != null
				&& hasText(musician.getUser().getUsername())) {
			raw = musician.getUser().getUsername();
		} else if (musician != null) {
			raw = musician.getStageName();
		}
		if (!hasText(raw)) {
			throw new SoundConnectException(ErrorType.INVALID_PARAMETER);
		}
		String normalized = raw.trim();
		if (normalized.length() > EventPerformerRequest.PERFORMER_NAME_SNAPSHOT_MAX_LENGTH) {
			throw new SoundConnectException(ErrorType.INVALID_PARAMETER);
		}
		return normalized;
	}

	private String venueName(Event event) {
		return event.getVenue() == null ? "Mekân" : safe(event.getVenue().getName(), "Mekân");
	}

	private String venueProfilePictureUrl(Event event) {
		if (event.getVenue() == null || event.getVenue().getId() == null) return null;
		return venueProfileRepository.findByVenueId(event.getVenue().getId())
				.map(profile -> {
					if (profile.getProfilePictureMediaId() == null) return null;
					try {
						return mediaAssetService.getDisplayUrl(profile.getProfilePictureMediaId());
					} catch (Exception exception) {
						log.debug("Venue avatar could not be resolved for event request. venueId={}, error={}",
								event.getVenue().getId(), exception.toString());
						return null;
					}
				})
				.orElse(null);
	}

	private String safe(String value, String fallback) {
		return hasText(value) ? value.trim() : fallback;
	}

	private boolean hasText(String value) {
		return value != null && !value.trim().isEmpty();
	}
}
