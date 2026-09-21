package com.berkayb.soundconnect.modules.event.plan;

import com.berkayb.soundconnect.modules.event.entity.Event;
import com.berkayb.soundconnect.modules.event.enums.*;
import com.berkayb.soundconnect.modules.event.mapper.EventMapper;
import com.berkayb.soundconnect.modules.event.repository.EventRepository;
import com.berkayb.soundconnect.modules.event.performer.service.EventPerformerRequestService;
import com.berkayb.soundconnect.modules.event.performer.outbox.EventPerformerNotificationOutboxPublisher;
import com.berkayb.soundconnect.modules.event.support.*;
import com.berkayb.soundconnect.modules.media.entity.MediaAsset;
import com.berkayb.soundconnect.modules.media.enums.*;
import com.berkayb.soundconnect.modules.media.repository.MediaAssetRepository;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.repository.MusicianProfileRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.*;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.*;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository.*;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.support.BandRepresentationPolicy;
import com.berkayb.soundconnect.modules.profile.VenueProfile.repository.VenueProfileRepository;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import com.berkayb.soundconnect.modules.venue.enums.VenueStatus;
import com.berkayb.soundconnect.modules.venue.repository.VenueRepository;
import com.berkayb.soundconnect.shared.exception.*;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationInboundEvent;
import com.berkayb.soundconnect.shared.response.PageResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/** One transaction and aggregate fence per mutation/materialization. No authentication context is used by generation. */
@Service @RequiredArgsConstructor
public class EventPlanService {
    private static final int MAX_ACTIVE_PLANS = 50;
    private final EventPlanRepository plans;
    private final EventPlanOccurrenceRepository occurrences;
    private final EventRepository events;
    private final VenueRepository venues;
    private final MusicianProfileRepository musicians;
    private final BandRepository bands;
    private final BandMemberRepository members;
    private final BandRepresentationPolicy representation;
    private final MediaAssetRepository assets;
    private final VenueProfileRepository venueProfiles;
    private final MediaAssetService media;
    private final EventMapper mapper;
    private final EventPerformerRequestService requests;
    private final EventPerformerNotificationOutboxPublisher notifications;
    private final EventScheduleClock clock;
    private final ObjectMapper json;

    @Transactional(readOnly = true)
    public EventPlanPreview preview(UUID actor, EventPlanDefinition raw) {
        EventPlanDefinition d = EventPlanRules.normalize(raw);
        requireVenue(actor, d.venueId(), true);
        validateTarget(d.template(), false);
        return EventPlanRules.preview(d, clock.instant());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public EventPlanPreview previewUpdate(UUID actor, UUID id, EventPlanUpdateRequest request) {
        if (request == null) throw invalid();
        EventPlanDefinition definition = EventPlanRules.normalize(request.definition());
        EventPlan plan = plans.findById(id).orElseThrow(this::notFound);
        requirePlanOwner(actor, plan);
        if (!plan.getVenueId().equals(definition.venueId())) throw invalid();
        requireVenue(actor, plan.getVenueId(), true);
        requireVersion(plan, request.expectedVersion());
        if (plan.getStatus() == EventPlanStatus.STOPPED) throw finalized();
        validateTarget(definition.template(), false);

        Instant now = clock.instant();
        EventPlanPreview preview = EventPlanRules.preview(definition, now);
        LocalDate from = today(now).isBefore(definition.startDate()) ? definition.startDate() : today(now);
        List<EventPlanPreservedDate> preserved = new ArrayList<>();
        Set<LocalDate> immutableDates = new HashSet<>();
        for (EventPlanPreviewOccurrence occurrence : occurrences.findPreviewWindow(id, from, preview.throughDate())) {
            EventPlanPreservationStatus reason = switch (occurrence.status()) {
                case SKIPPED -> EventPlanPreservationStatus.SKIPPED;
                case CANCELLED -> EventPlanPreservationStatus.CANCELLED;
                case OVERRIDDEN -> occurrence.liveEventId() == null
                        ? EventPlanPreservationStatus.CANCELLED : EventPlanPreservationStatus.OVERRIDDEN;
                case GENERATED -> occurrence.liveEventId() == null ? EventPlanPreservationStatus.CANCELLED
                        : !EventPlanRules.startsAt(occurrence.eventDate(), occurrence.startTime()).isAfter(now)
                        ? EventPlanPreservationStatus.STARTED : null;
            };
            if (reason != null) {
                immutableDates.add(occurrence.scheduledDate());
                preserved.add(new EventPlanPreservedDate(occurrence.scheduledDate(), occurrence.eventDate(), reason));
            }
        }
        return new EventPlanPreview(preview.dates().stream().filter(date -> !immutableDates.contains(date)).toList(),
                preview.throughDate(), preview.hasMore(), now, List.copyOf(preserved));
    }

    @Transactional
    public EventPlanResponse create(UUID actor, EventPlanCreateRequest request) {
        if (actor == null || request == null || request.clientRequestId() == null) throw invalid();
        EventPlanDefinition d = EventPlanRules.normalize(request.definition());
        plans.lockOwner(actor).orElseThrow(this::notFound);
        Venue venue = requireVenue(actor, d.venueId(), true);
        String hash = hash(d);
        EventPlan existing = plans.findByOrganizerUserIdAndClientRequestId(actor, request.clientRequestId()).orElse(null);
        if (existing != null) {
            if (!existing.getCreationHash().equals(hash)) throw conflict();
            return response(existing);
        }
        if (!EventPlanRules.hasFuture(d, clock.instant())) throw invalid();
        if (effectiveActiveIds(actor, null, clock.instant()).size() >= MAX_ACTIVE_PLANS) {
            throw new SoundConnectException(ErrorType.EVENT_PLAN_LIMIT_REACHED);
        }
        validatePoster(d.template().posterImage(), venue);
        lockBands(null, d.template().bandId());
        String name = validateTarget(d.template(), true);
        EventPlan plan = new EventPlan();
        plan.setOrganizerUserId(actor); plan.setVenueId(d.venueId());
        plan.setClientRequestId(request.clientRequestId()); plan.setCreationHash(hash);
        plan.setStatus(EventPlanStatus.ACTIVE); applyDefinition(plan, d, name);
        resetConsent(plan);
        plans.saveAndFlush(plan);
        requestConsent(plan);
        materialize(plan, venue);
        return response(plan);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public EventPlanResponse getOwner(UUID actor, UUID id) {
        EventPlan plan = plans.findById(id).orElseThrow(this::notFound);
        requirePlanOwner(actor, plan);
        return response(plan);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResponse<EventPlanResponse> getOwnerPlans(UUID actor, UUID venueId, int page, int size) {
        validatePage(page, size); requireVenue(actor, venueId, false);
        Instant now = clock.instant();
        List<UUID> activeIds = effectiveActiveIds(null, venueId, now);
        return PageResponse.from(plans.findOwnerPlans(venueId, activeIds, PageRequest.of(page, size)).map(this::response));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResponse<EventPlanResponse> getPerformerPlans(UUID actor, PerformerType type, UUID targetId, int page, int size) {
        validatePage(page, size); requireTargetActor(actor, type, targetId);
        return PageResponse.from((type == PerformerType.BAND
                ? plans.findByBandIdOrderByCreatedAtDescIdDesc(targetId, PageRequest.of(page, size))
                : plans.findByMusicianProfileIdOrderByCreatedAtDescIdDesc(targetId, PageRequest.of(page, size))).map(this::response));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public EventPlanResponse getPerformer(UUID actor, UUID id) {
        EventPlan plan = plans.findById(id).orElseThrow(this::notFound);
        requirePerformer(actor, plan); return response(plan);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResponse<EventPlanOccurrenceResponse> getOccurrences(UUID actor, UUID id, int page, int size) {
        validatePage(page, size);
        EventPlan plan = plans.findById(id).orElseThrow(this::notFound); requirePlanOwner(actor, plan);
        return PageResponse.from(occurrences.findByIdPlanIdOrderByIdScheduledDateDesc(id, PageRequest.of(page, size)).map(o -> {
            Event event = o.getEventId() == null ? null : events.findById(o.getEventId()).orElse(null);
            EventPlanOccurrenceStatus status = event == null && (o.getStatus() == EventPlanOccurrenceStatus.GENERATED || o.getStatus() == EventPlanOccurrenceStatus.OVERRIDDEN)
                    ? EventPlanOccurrenceStatus.CANCELLED : o.getStatus();
            return new EventPlanOccurrenceResponse(o.getId().getScheduledDate(), event == null ? null : event.getId(),
                    event == null ? o.getEventDate() : event.getEventDate(), status,
                    event == null ? null : mapper.toDto(event), event == null ? null : occurrenceTemplate(plan, event, o));
        }));
    }

    @Transactional
    public EventPlanResponse update(UUID actor, UUID id, EventPlanUpdateRequest request) {
        if (request == null) throw invalid();
        EventPlanDefinition d = EventPlanRules.normalize(request.definition());
        EventPlanAuthority auth = ownerPreflight(actor, id);
        plans.lockOwner(actor).orElseThrow(this::notFound);
        if (!auth.venueId().equals(d.venueId())) throw invalid();
        Venue venue = requireVenue(actor, auth.venueId(), true);
        validatePoster(d.template().posterImage(), venue);
        lockBands(auth.bandId(), d.template().bandId());
        EventPlan plan = lockedOwner(actor, id);
        requireSameAuthority(plan, auth);
        requireVersion(plan, request.expectedVersion());
        if (plan.getStatus() == EventPlanStatus.STOPPED) throw finalized();
        if(effectiveStatus(plan)==EventPlanStatus.COMPLETED
                && effectiveActiveIds(actor, null, clock.instant()).size()>=MAX_ACTIVE_PLANS) {
            throw new SoundConnectException(ErrorType.EVENT_PLAN_LIMIT_REACHED);
        }
        if (!EventPlanRules.hasFuture(d, clock.instant())) throw invalid();
        EventPlanDefinition previous = definition(plan);
        String name = validateTarget(d.template(), true);
        boolean reset = effectiveStatus(plan) == EventPlanStatus.COMPLETED || EventPlanRules.consentScopeChanged(previous, d);
        plan.setStatus(EventPlanStatus.ACTIVE);
        applyDefinition(plan, d, name);
        if (reset) resetConsent(plan);
        bump(plan);
        for (EventPlanOccurrence o : futureOccurrences(plan)) {
            if (o.getStatus() != EventPlanOccurrenceStatus.GENERATED || o.getEventId() == null) continue;
            Event event = events.findByIdForUpdate(o.getEventId()).orElse(null);
            if (event == null || !beforeStart(event)) continue;
            if (!EventPlanRules.matches(d, o.getId().getScheduledDate())
                    || !EventPlanRules.startsAt(o.getId().getScheduledDate(), d.template().startTime()).isAfter(clock.instant())) {
                cancel(o, event); continue;
            }
            if (reset) resetPublications(event);
            applyContent(event, d.template(), o.getId().getScheduledDate());
            snapshotTarget(o,d.template());
            if (reset) applySeriesConsent(plan, event);
        }
        if (reset) requestConsent(plan);
        materialize(plan, venue);
        return response(plan);
    }

    @Transactional
    public EventPlanResponse stop(UUID actor, UUID id, EventPlanStopRequest request) {
        if (request == null || request.cancelFuture() == null) throw invalid();
        EventPlanAuthority auth = ownerPreflight(actor, id); lockBands(auth.bandId(), null);
        EventPlan plan = lockedOwner(actor, id);
        requireSameAuthority(plan, auth);
        if (plan.getStatus() == EventPlanStatus.STOPPED && plan.isCancelFuture() == request.cancelFuture()
                && request.expectedVersion() != null && request.expectedVersion() + 1 == plan.getVersion()) return response(plan);
        requireVersion(plan, request.expectedVersion());
        if (plan.getStatus() != EventPlanStatus.STOPPED || request.cancelFuture() && !plan.isCancelFuture()) {
            plan.setStatus(EventPlanStatus.STOPPED); plan.setCancelFuture(plan.isCancelFuture() || request.cancelFuture()); bump(plan);
            if (request.cancelFuture()) for (EventPlanOccurrence o : futureOccurrences(plan)) {
                if (o.getEventId() == null) continue;
                Event event = events.findByIdForUpdate(o.getEventId()).orElse(null);
                if (event != null && beforeStart(event)) cancel(o, event);
            }
        }
        return response(plan);
    }

    @Transactional
    public EventPlanResponse skip(UUID actor, UUID id, LocalDate date, long expectedVersion) {
        EventPlanAuthority auth = ownerPreflight(actor, id); lockBands(auth.bandId(), null);
        EventPlan plan = lockedOwner(actor, id); requireSameAuthority(plan, auth); requireVersion(plan, expectedVersion);
        if (!EventPlanRules.validDate(date)) throw invalid();
        EventPlanOccurrence o = occurrences.findById(new EventPlanOccurrence.Id(id, date)).orElse(null);
        if (o != null && (o.getStatus() == EventPlanOccurrenceStatus.SKIPPED || o.getStatus() == EventPlanOccurrenceStatus.CANCELLED)) return response(plan);
        if (o == null) {
            if (!EventPlanRules.matches(definition(plan), date) || !EventPlanRules.startsAt(date, plan.getStartTime()).isAfter(clock.instant())) throw invalid();
            o = new EventPlanOccurrence(id, date);
        } else if (o.getEventId() != null) {
            Event event = events.findByIdForUpdate(o.getEventId()).orElse(null);
            if (event != null) { if (!beforeStart(event)) throw finalized(); cancel(o, event); }
        }
        o.setStatus(EventPlanOccurrenceStatus.SKIPPED); o.setEventId(null); occurrences.save(o); bump(plan);
        return response(plan);
    }

    @Transactional
    public EventPlanResponse override(UUID actor, UUID id, LocalDate scheduledDate, EventPlanOverrideRequest request) {
        if (request == null || !EventPlanRules.validDate(request.eventDate())) throw invalid();
        EventPlanTemplate template = EventPlanRules.normalizeTemplate(request.template());
        EventPlanAuthority auth = ownerPreflight(actor, id);
        Venue venue = requireVenue(actor, auth.venueId(), true);
        validatePoster(template.posterImage(), venue);
        UUID previousOverrideBand=occurrences.findOverrideBandId(id,scheduledDate).orElse(null);
        lockBands(auth.bandId(), template.bandId(), previousOverrideBand);
        EventPlan plan = lockedOwner(actor, id); requireSameAuthority(plan, auth); requireVersion(plan, request.expectedVersion());
        EventPlanOccurrence o = occurrences.findById(new EventPlanOccurrence.Id(id, scheduledDate)).orElseThrow(this::notFound);
        if(!Objects.equals(previousOverrideBand,o.getOverrideBandId()))throw conflict();
        if (o.getEventId() == null) throw notFound();
        Event event = events.findByIdForUpdate(o.getEventId()).orElseThrow(this::notFound);
        if (!beforeStart(event) || !EventPlanRules.startsAt(request.eventDate(), template.startTime()).isAfter(clock.instant())) throw finalized();
        String name = validateTarget(template, true);
        boolean reset = !event.getEventDate().equals(request.eventDate())
                || EventPlanRules.performerOrScheduleChanged(occurrenceTemplate(plan, event, o), template)
                || (o.getStatus()==EventPlanOccurrenceStatus.GENERATED
                    && plan.getConsentStatus()!=EventPlanConsentStatus.ACCEPTED && plan.getConsentStatus()!=EventPlanConsentStatus.NOT_REQUIRED);
        if (reset) { resetPublications(event); requests.deleteForEvent(event.getId()); events.flush(); }
        applyContent(event, template, request.eventDate());
        if (reset) applySingleConsent(actor, event, template, name);
        o.setEventDate(request.eventDate()); o.setStatus(EventPlanOccurrenceStatus.OVERRIDDEN);
        snapshotTarget(o,template); bump(plan);
        return response(plan);
    }

    @Transactional
    public EventPlanResponse decide(UUID actor, UUID id, EventPlanDecisionRequest request) {
        if (request == null || request.decision() == null
                || request.decision() == EventPlanDecision.ACCEPT && request.showOnProfile() == null
                || request.decision() != EventPlanDecision.ACCEPT && request.showOnProfile() != null) throw invalid();
        EventPlanAuthority auth = plans.findAuthority(id).orElseThrow(this::notFound);
        requireTargetActor(actor, auth.bandId() == null ? PerformerType.MUSICIAN : PerformerType.BAND,
                auth.bandId() == null ? auth.musicianProfileId() : auth.bandId());
        lockBands(auth.bandId(), null);
        EventPlan plan = plans.findByIdForUpdate(id).orElseThrow(this::notFound); requirePerformer(actor, plan);
        requireSameAuthority(plan, auth);
        EventPlanConsentStatus desired = switch (request.decision()) {
            case ACCEPT -> EventPlanConsentStatus.ACCEPTED;
            case REJECT -> EventPlanConsentStatus.REJECTED;
            case WITHDRAW -> EventPlanConsentStatus.WITHDRAWN;
        };
        if (plan.getConsentStatus() == desired && (desired != EventPlanConsentStatus.ACCEPTED
                || Objects.equals(plan.getAcceptedPublication(), request.showOnProfile()))
                && request.expectedVersion() != null && request.expectedVersion() + 1 == plan.getVersion()) return response(plan);
        requireVersion(plan, request.expectedVersion());
        if (request.decision() == EventPlanDecision.WITHDRAW) {
            if (!withdrawAllowed(plan)) throw finalized();
        } else if (!decisionAllowed(plan)) throw finalized();
        plan.setConsentStatus(desired); plan.setDecidedByUserId(actor);
        plan.setShowOnProfile(desired == EventPlanConsentStatus.ACCEPTED && request.showOnProfile());
        if (desired == EventPlanConsentStatus.ACCEPTED) plan.setAcceptedPublication(request.showOnProfile());
        bump(plan);
        for (EventPlanOccurrence o : futureOccurrences(plan)) {
            if (o.getStatus() != EventPlanOccurrenceStatus.GENERATED || o.getEventId() == null) continue;
            Event event = events.findByIdForUpdate(o.getEventId()).orElse(null);
            if (event == null || !beforeStart(event)) continue;
            resetPublications(event); applySeriesConsent(plan, event);
        }
        decisionNotification(plan, request.decision());
        return response(plan);
    }

    /** Invoked through the Spring proxy, one transaction per plan; scheduler never holds a batch transaction. */
    @Transactional
    public void generate(UUID id) {
        EventPlanAuthority auth = plans.findAuthority(id).orElse(null);
        if (auth == null) return;
        lockBands(auth.bandId(), null);
        EventPlan plan = plans.findByIdForUpdate(id).orElse(null);
        if (plan == null || plan.getStatus() != EventPlanStatus.ACTIVE) return;
        requireSameAuthority(plan, auth);
        Venue venue = venues.findById(plan.getVenueId()).orElse(null);
        if (venue == null || !venueEligible(venue) || !plan.getOrganizerUserId().equals(venue.getOwner().getId())
                || !targetExists(plan)) {
            plan.setStatus(EventPlanStatus.STOPPED);
            if (!targetExists(plan)) { plan.setConsentStatus(EventPlanConsentStatus.WITHDRAWN); plan.setShowOnProfile(false); }
            bump(plan); return;
        }
        materialize(plan, venue);
    }

    private void materialize(EventPlan plan, Venue venue) {
        if (plan.getStatus() != EventPlanStatus.ACTIVE) return;
        Instant now = clock.instant();
        EventPlanDefinition d = definition(plan);
        if (!EventPlanRules.hasFuture(d, now)) { plan.setStatus(EventPlanStatus.COMPLETED); return; }
        for (LocalDate date : EventPlanRules.dates(d, now)) {
            var key = new EventPlanOccurrence.Id(plan.getId(), date);
            // Every existing ledger row is authoritative, including SET NULL deletion tombstones.
            if (occurrences.existsById(key)) continue;
            Event event = Event.builder().eventOrigin(EventOrigin.VENUE).organizerUserId(plan.getOrganizerUserId()).venue(venue).build();
            applyContent(event, d.template(), date); applySeriesConsent(plan, event);
            events.saveAndFlush(event);
            EventPlanOccurrence o = new EventPlanOccurrence(plan.getId(), date);
            o.setEventId(event.getId()); o.setStatus(EventPlanOccurrenceStatus.GENERATED);
            snapshotTarget(o,d.template());
            occurrences.saveAndFlush(o);
        }
        plan.setGeneratedThrough(today(now).plusDays(EventPlanRules.HORIZON_DAYS - 1));
    }

    private void applySeriesConsent(EventPlan plan, Event event) {
        event.setMusicianProfile(null); event.setBand(null);
        event.setManualPerformerName(plan.getPerformerNameSnapshot()); event.setProfileCalendarApproved(false);
        if (plan.getConsentStatus() == EventPlanConsentStatus.ACCEPTED) {
            if (plan.getBandId() != null) event.setBand(bands.findById(plan.getBandId()).orElseThrow(this::notFound));
            else event.setMusicianProfile(musicians.findById(plan.getMusicianProfileId()).orElseThrow(this::notFound));
            event.setManualPerformerName(null); event.setPerformerApprovalStatus(EventPerformerApprovalStatus.APPROVED);
            event.setProfileCalendarApproved(plan.isShowOnProfile());
        } else if (plan.getConsentStatus() == EventPlanConsentStatus.PENDING) {
            event.setPerformerApprovalStatus(EventPerformerApprovalStatus.PENDING);
        } else if (plan.getConsentStatus() == EventPlanConsentStatus.REJECTED || plan.getConsentStatus() == EventPlanConsentStatus.WITHDRAWN) {
            event.setPerformerApprovalStatus(EventPerformerApprovalStatus.REJECTED);
        } else event.setPerformerApprovalStatus(EventPerformerApprovalStatus.NOT_REQUIRED);
    }

    private void applySingleConsent(UUID actor, Event event, EventPlanTemplate t, String snapshot) {
        event.setMusicianProfile(null); event.setBand(null); event.setManualPerformerName(snapshot);
        event.setPerformerApprovalStatus(EventPerformerApprovalStatus.NOT_REQUIRED); event.setProfileCalendarApproved(false);
        MusicianProfile musician = t.musicianProfileId() == null ? null : musicians.findById(t.musicianProfileId()).orElseThrow(this::notFound);
        Band band = t.bandId() == null ? null : bands.findById(t.bandId()).orElseThrow(this::notFound);
        if (musician == null && band == null) return;
        boolean connected = musician != null
                ? musicians.lockActiveVenueConnection(musician.getId(), event.getVenue().getId()).isPresent()
                : venues.lockActiveBandConnection(event.getVenue().getId(), band.getId()).isPresent();
        if (connected) {
            event.setMusicianProfile(musician); event.setBand(band); event.setManualPerformerName(null);
            event.setPerformerApprovalStatus(EventPerformerApprovalStatus.APPROVED);
            events.saveAndFlush(event); requests.createProfileVisibilityRequest(actor, event, musician, band);
        } else {
            event.setPerformerApprovalStatus(EventPerformerApprovalStatus.PENDING);
            events.saveAndFlush(event); requests.createPendingRequest(actor, event, musician, band);
        }
    }

    private void resetPublications(Event event) {
        if (event.getBand() != null) occurrences.resetMemberPublications(event.getId());
        event.setProfileCalendarApproved(false);
        event.setProfilePublicationVersion(event.getProfilePublicationVersion() + 1);
    }

    private void cancel(EventPlanOccurrence o, Event event) {
        requests.deleteForEvent(event.getId());
        o.setEventId(null); o.setStatus(EventPlanOccurrenceStatus.CANCELLED);
        occurrences.saveAndFlush(o); events.delete(event);
    }

    private void applyContent(Event e, EventPlanTemplate t, LocalDate date) {
        e.setTitle(t.title()); e.setDescription(t.description()); e.setEventDate(date);
        e.setStartTime(t.startTime()); e.setEndTime(t.endTime()); e.setPosterImage(t.posterImage());
    }

    private void applyDefinition(EventPlan p, EventPlanDefinition d, String name) {
        p.setStartDate(d.startDate()); p.setUntilDate(d.untilDate());
        p.setWeekdayMask(d.weekdays().stream().mapToInt(day -> 1 << (day - 1)).reduce(0, (a,b) -> a | b));
        p.setExcludedDates(d.excludedDates().stream().map(LocalDate::toString).toList());
        EventPlanTemplate t = d.template();
        p.setTitle(t.title()); p.setDescription(t.description()); p.setStartTime(t.startTime()); p.setEndTime(t.endTime());
        p.setPosterImage(t.posterImage()); p.setMusicianProfileId(t.musicianProfileId()); p.setBandId(t.bandId());
        p.setManualPerformerName(t.manualPerformerName()); p.setPerformerNameSnapshot(name);
    }

    private void resetConsent(EventPlan p) {
        p.setConsentRevision(p.getConsentRevision() + 1);
        p.setConsentStatus(p.getMusicianProfileId() != null || p.getBandId() != null
                ? EventPlanConsentStatus.PENDING : EventPlanConsentStatus.NOT_REQUIRED);
        p.setShowOnProfile(false); p.setAcceptedPublication(null); p.setDecidedByUserId(null);
    }

    private EventPlanDefinition definition(EventPlan p) {
        List<Integer> days = new ArrayList<>();
        for (int day = 1; day <= 7; day++) {
            if ((p.getWeekdayMask() & (1 << (day - 1))) != 0) days.add(day);
        }
        return new EventPlanDefinition(p.getVenueId(), p.getStartDate(), p.getUntilDate(), List.copyOf(days),
                p.getExcludedDates().stream().map(LocalDate::parse).toList(),
                new EventPlanTemplate(p.getTitle(),p.getDescription(),p.getStartTime(),p.getEndTime(),p.getPosterImage(),
                        p.getMusicianProfileId(),p.getBandId(),p.getManualPerformerName()));
    }

    private EventPlanTemplate occurrenceTemplate(EventPlan p, Event event, EventPlanOccurrence o) {
        return new EventPlanTemplate(event.getTitle(), event.getDescription(), event.getStartTime(), event.getEndTime(),
                event.getPosterImage(), o.getOverrideMusicianProfileId(), o.getOverrideBandId(), o.getOverrideManualPerformerName());
    }

    private void snapshotTarget(EventPlanOccurrence occurrence, EventPlanTemplate template) {
        occurrence.setOverrideMusicianProfileId(template.musicianProfileId());
        occurrence.setOverrideBandId(template.bandId());
        occurrence.setOverrideManualPerformerName(template.manualPerformerName());
    }

    private EventPlanResponse response(EventPlan p) {
        Venue v = venues.findById(p.getVenueId()).orElseThrow(this::notFound);
        return new EventPlanResponse(p.getId(),p.getVersion(),definition(p),v.getName(),p.getPerformerNameSnapshot(),
                EventPosterResolver.resolve(p.getPosterImage(),media),effectiveStatus(p),p.getConsentStatus(),p.isShowOnProfile(),
                p.getGeneratedThrough(),clock.instant(),decisionAllowed(p),withdrawAllowed(p));
    }

    private boolean decisionAllowed(EventPlan p) {
        return effectiveStatus(p) == EventPlanStatus.ACTIVE && targetExists(p)
                && (p.getConsentStatus() == EventPlanConsentStatus.PENDING || p.getConsentStatus() == EventPlanConsentStatus.REJECTED)
                && EventPlanRules.hasFuture(definition(p),clock.instant());
    }
    private boolean withdrawAllowed(EventPlan p) {
        if (p.getConsentStatus() != EventPlanConsentStatus.ACCEPTED) return false;
        if (effectiveStatus(p) == EventPlanStatus.ACTIVE && EventPlanRules.hasFuture(definition(p),clock.instant())) return true;
        Instant now = clock.instant();
        // A scalar projection avoids stale persistence-context state when the later mutation locks events.
        // Compare wall times in Java: legacy Hibernate UTC TIME storage can wrap around midnight.
        return occurrences.findGeneratedStarts(p.getId(), today(now)).stream()
                .anyMatch(start -> EventPlanRules.startsAt(start.eventDate(), start.startTime()).isAfter(now));
    }
    private EventPlanStatus effectiveStatus(EventPlan p) {
        return p.getStatus() == EventPlanStatus.ACTIVE && !EventPlanRules.hasFuture(definition(p),clock.instant())
                ? EventPlanStatus.COMPLETED : p.getStatus();
    }

    private List<UUID> effectiveActiveIds(UUID ownerId, UUID venueId, Instant now) {
        List<UUID> activeIds = new ArrayList<>();
        UUID after = null;
        while (activeIds.size() < MAX_ACTIVE_PLANS) {
            List<EventPlan> candidates = plans.findPotentiallyActiveAfter(ownerId, venueId, today(now), after,
                    PageRequest.of(0, MAX_ACTIVE_PLANS));
            if (candidates.isEmpty()) break;
            for (EventPlan candidate : candidates) {
                if (EventPlanRules.hasFuture(definition(candidate), now)) {
                    activeIds.add(candidate.getId());
                    if (activeIds.size() == MAX_ACTIVE_PLANS) break;
                }
            }
            if (candidates.size() < MAX_ACTIVE_PLANS) break;
            // Completion by the scheduler may remove candidates between reads. A UUID
            // cursor keeps that harmless; offset paging could skip a still-active plan.
            after = candidates.getLast().getId();
        }
        return List.copyOf(activeIds);
    }
    private List<EventPlanOccurrence> futureOccurrences(EventPlan p) {
        return occurrences.findByIdPlanIdAndEventDateGreaterThanEqualOrderByIdScheduledDate(p.getId(), today(clock.instant()));
    }
    private boolean beforeStart(Event event) {
        Instant startsAt = clock.startsAt(event);
        return startsAt != null && clock.instant().isBefore(startsAt);
    }

    private EventPlanAuthority ownerPreflight(UUID actor, UUID id) {
        EventPlanAuthority a = plans.findAuthority(id).orElseThrow(this::notFound);
        if (!a.organizerUserId().equals(actor)) throw notFound();
        requireVenue(actor, a.venueId(), false);
        return a;
    }
    private EventPlan lockedOwner(UUID actor, UUID id) {
        EventPlan plan = plans.findByIdForUpdate(id).orElseThrow(this::notFound);
        requirePlanOwner(actor, plan);
        return plan;
    }
    private void requirePlanOwner(UUID actor, EventPlan p) {
        if (!p.getOrganizerUserId().equals(actor)) throw notFound();
        requireVenue(actor, p.getVenueId(), false);
    }
    private Venue requireVenue(UUID actor, UUID venueId, boolean approved) {
        if (actor == null || venueId == null) throw notFound();
        Venue v = venues.findById(venueId).orElseThrow(this::notFound);
        if (v.getOwner() == null || !actor.equals(v.getOwner().getId())) throw notFound();
        if (approved && !venueEligible(v)) throw invalid();
        return v;
    }
    private boolean venueEligible(Venue v) {
        return v.getStatus() == VenueStatus.APPROVED && v.getOwner() != null
                && v.getOwner().getStatus() == UserStatus.ACTIVE && Boolean.TRUE.equals(v.getOwner().getEmailVerified());
    }
    private void requirePerformer(UUID actor, EventPlan p) {
        requireTargetActor(actor,p.getBandId() == null ? PerformerType.MUSICIAN : PerformerType.BAND,
                p.getBandId() == null ? p.getMusicianProfileId() : p.getBandId());
    }
    private void requireTargetActor(UUID actor, PerformerType type, UUID target) {
        if (actor == null || target == null || type == null || type == PerformerType.MANUAL) throw notFound();
        if (type == PerformerType.BAND) {
            if (!representation.canRepresent(actor, target)) throw notFound();
        } else if (musicians.findById(target)
                .filter(m -> m.getUser() != null && actor.equals(m.getUser().getId())).isEmpty()) {
            throw notFound();
        }
    }
    private void requireSameAuthority(EventPlan p, EventPlanAuthority a) {
        if (!Objects.equals(p.getBandId(), a.bandId()) || !Objects.equals(p.getMusicianProfileId(), a.musicianProfileId())) {
            throw conflict();
        }
    }
    private void lockBands(UUID... values) {
        TreeSet<UUID> ids = new TreeSet<>();
        for (UUID value : values) if (value != null) ids.add(value);
        for (UUID id : ids) bands.findByIdForUpdate(id);
    }
    private String validateTarget(EventPlanTemplate t, boolean requiresDecider) {
        if (t.bandId() != null) {
            Band band = bands.findById(t.bandId()).orElseThrow(this::notFound);
            if (requiresDecider && founderIds(band.getId()).isEmpty()) throw invalid();
            return snapshot(band.getName());
        }
        if (t.musicianProfileId() != null) {
            MusicianProfile m = musicians.findById(t.musicianProfileId()).orElseThrow(this::notFound);
            if (m.getUser() == null || m.getUser().getStatus() != UserStatus.ACTIVE || !Boolean.TRUE.equals(m.getUser().getEmailVerified())) throw invalid();
            return snapshot(m.getUser().getUsername() == null || m.getUser().getUsername().isBlank() ? m.getStageName() : m.getUser().getUsername());
        }
        return t.manualPerformerName();
    }
    private boolean targetExists(EventPlan p) {
        if (p.getBandId() != null) return bands.existsById(p.getBandId());
        if (p.getMusicianProfileId() != null) {
            return musicians.findById(p.getMusicianProfileId()).filter(m -> m.getUser() != null
                    && m.getUser().getStatus() == UserStatus.ACTIVE
                    && Boolean.TRUE.equals(m.getUser().getEmailVerified())).isPresent();
        }
        return p.getConsentStatus() == EventPlanConsentStatus.NOT_REQUIRED;
    }

    private String snapshot(String name) {
        if (name == null || name.isBlank() || name.trim().length() > 100) throw invalid();
        return name.trim();
    }

    private List<UUID> founderIds(UUID id) {
        return members.findByBandId(id).stream()
                .filter(m -> m.getStatus() == BandMemberShipStatus.ACTIVE && m.getBandRole() == BandRole.FOUNDER)
                .map(BandMember::getUser).filter(Objects::nonNull)
                .filter(u -> u.getStatus() == UserStatus.ACTIVE && Boolean.TRUE.equals(u.getEmailVerified()))
                .map(u -> u.getId()).filter(Objects::nonNull).distinct().toList();
    }

    private void validatePoster(String ref, Venue venue) {
        if (ref == null) return;
        final UUID id;
        try {
            id = UUID.fromString(ref);
        } catch (IllegalArgumentException legacyReference) {
            return;
        }
        MediaAsset asset = assets.findByIdForUpdate(id)
                .orElseThrow(() -> new SoundConnectException(ErrorType.MEDIA_ASSET_NOT_FOUND));
        UUID owner = venueProfiles.findByVenueId(venue.getId()).map(p -> p.getId()).orElseThrow(this::notFound);
        if (asset.getKind() != MediaKind.IMAGE) throw new SoundConnectException(ErrorType.MEDIA_KIND_INVALID);
        if (asset.getStatus() != MediaStatus.READY) throw new SoundConnectException(ErrorType.MEDIA_ASSET_NOT_READY);
        if (asset.getVisibility() != MediaVisibility.PUBLIC) throw new SoundConnectException(ErrorType.MEDIA_ASSET_NOT_PUBLIC);
        if (asset.getOwnerType() != MediaOwnerType.VENUE_PROFILE || !owner.equals(asset.getOwnerId())) {
            throw new SoundConnectException(ErrorType.MEDIA_ASSET_OWNER_MISMATCH);
        }
        if ((asset.getPlaybackUrl() == null || asset.getPlaybackUrl().isBlank())
                && (asset.getSourceUrl() == null || asset.getSourceUrl().isBlank())) {
            throw new SoundConnectException(ErrorType.MEDIA_ASSET_STATE_INVALID);
        }
    }

    private void requestConsent(EventPlan p) {
        if (p.getConsentStatus() != EventPlanConsentStatus.PENDING) return;
        List<UUID> recipients = p.getBandId() != null ? founderIds(p.getBandId())
                : List.of(musicians.findById(p.getMusicianProfileId()).orElseThrow(this::notFound).getUser().getId());
        notify(p, recipients, NotificationType.EVENT_PERFORMER_APPROVAL_REQUESTED, "REQUESTED", "Program katılım onayı",
                "“" + p.getTitle() + "” programına katılımın için onayın bekleniyor.");
    }

    private void decisionNotification(EventPlan p, EventPlanDecision decision) {
        NotificationType type = decision == EventPlanDecision.ACCEPT
                ? NotificationType.EVENT_PERFORMER_APPROVED : NotificationType.EVENT_PERFORMER_REJECTED;
        String title = switch (decision) {
            case ACCEPT -> "Program katılımı onaylandı";
            case REJECT -> "Program katılımı reddedildi";
            case WITHDRAW -> "Program katılımı geri çekildi";
        };
        notify(p, List.of(p.getOrganizerUserId()), type, decision.name(), title,
                p.getPerformerNameSnapshot() + ", “" + p.getTitle() + "” programı için katılım kararını güncelledi.");
    }

    private void notify(EventPlan p, List<UUID> recipients, NotificationType type, String action, String title, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("module", "EVENT_PLAN");
        payload.put("planId", p.getId().toString());
        payload.put("venueId", p.getVenueId().toString());
        payload.put("action", action);
        payload.put("consentStatus", p.getConsentStatus().name());
        payload.put("consentRevision", p.getConsentRevision());
        payload.put("targetType", p.getBandId() != null ? "BAND" : "MUSICIAN");
        if (p.getBandId() != null) payload.put("targetId", p.getBandId().toString());
        if (p.getMusicianProfileId() != null) payload.put("targetId", p.getMusicianProfileId().toString());
        notifications.enqueueAll(recipients.stream().distinct().map(recipient -> NotificationInboundEvent.builder()
                .eventId(UUID.nameUUIDFromBytes(("EVENT_PLAN|" + p.getId() + "|" + p.getConsentRevision() + "|" + action + "|" + recipient)
                        .getBytes(StandardCharsets.UTF_8)))
                .recipientId(recipient).type(type).title(title).message(message).payload(Map.copyOf(payload))
                .emailForce(false).occurredAt(clock.instant()).build()).toList());
    }

    private String hash(EventPlanDefinition d) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(d)));
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot hash normalized event plan", ex);
        }
    }

    private void requireVersion(EventPlan p, Long version) {
        if (version == null || version < 0 || version == Long.MAX_VALUE) throw invalid();
        if (version != p.getVersion()) throw conflict();
    }

    private void bump(EventPlan p) {
        if (p.getVersion() == Long.MAX_VALUE) throw conflict();
        p.setVersion(p.getVersion() + 1);
    }

    private static LocalDate today(Instant now) {
        return now.atZone(EventScheduleClock.ZONE).toLocalDate();
    }

    private void validatePage(int page, int size) {
        if (page < 0 || page > 100 || size < 1 || size > 50) throw invalid();
    }

    private SoundConnectException invalid() { return new SoundConnectException(ErrorType.INVALID_PARAMETER); }
    private SoundConnectException conflict() { return new SoundConnectException(ErrorType.MUSICIAN_CALENDAR_VERSION_CONFLICT); }
    private SoundConnectException notFound() { return new SoundConnectException(ErrorType.EVENT_NOT_FOUND); }
    private SoundConnectException finalized() { return new SoundConnectException(ErrorType.EVENT_PERFORMER_REQUEST_FINALIZED); }
}
