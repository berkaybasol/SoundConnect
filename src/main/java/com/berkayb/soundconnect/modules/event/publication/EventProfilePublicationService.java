package com.berkayb.soundconnect.modules.event.publication;

import com.berkayb.soundconnect.modules.event.entity.Event;
import com.berkayb.soundconnect.modules.event.enums.PerformerType;
import com.berkayb.soundconnect.modules.event.repository.EventRepository;
import com.berkayb.soundconnect.modules.event.support.EventPosterResolver;
import com.berkayb.soundconnect.modules.event.support.EventScheduleClock;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.calendar.BandCalendarSettingsRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.calendar.repository.MusicianCalendarSettingsRepository;
import com.berkayb.soundconnect.shared.exception.*;
import com.berkayb.soundconnect.shared.response.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service @RequiredArgsConstructor
public class EventProfilePublicationService {
    private final EventProfilePublicationRepository publications;
    private final EventMemberPublicationRepository memberPublications;
    private final EventRepository events;
    private final MusicianCalendarSettingsRepository profiles;
    private final BandCalendarSettingsRepository bands;
    private final MediaAssetService media;
    private final EventScheduleClock scheduleClock;

    /** Preflight before shared rate-limit consumption. Authority is checked again under locks. */
    @Transactional
    public void requireAuthority(UUID userId, PerformerType type, UUID targetId) {
        validateTarget(userId, type, targetId);
        if (type == PerformerType.BAND) {
            if (!bands.isActiveFounder(targetId, userId)) throw forbidden();
        } else if (profiles.findOwnedProfileId(userId).filter(targetId::equals).isEmpty()) {
            throw forbidden();
        }
    }

    @Transactional
    public PageResponse<EventProfilePublicationDto> getMine(UUID userId, PerformerType type, UUID targetId, int page, int size) {
        return getMine(userId, type, targetId, page, size, EventPublicationPeriod.ALL);
    }

    @Transactional
    public PageResponse<EventProfilePublicationDto> getMine(UUID userId, PerformerType type, UUID targetId,
            int page, int size, EventPublicationPeriod period) {
        validateTarget(userId, type, targetId);
        if (page < 0 || page > 100 || size < 1 || size > 50 || period == null) throw invalid();
        lockTarget(userId, type, targetId, false);
        var now = scheduleClock.localNow();
        var weekEnd = now.toLocalDate().plusDays(6);
        var pageable = PageRequest.of(page, size);
        var ids = period != EventPublicationPeriod.ALL
                ? (type == PerformerType.BAND
                    ? publications.findForBandPeriod(targetId, period.name(), now.toLocalDate(), now.toLocalTime(),
                        java.time.LocalTime.MIDNIGHT, weekEnd, pageable)
                    : publications.findForMusicianPeriod(targetId, period.name(), now.toLocalDate(), now.toLocalTime(),
                        java.time.LocalTime.MIDNIGHT, weekEnd, pageable))
                : type == PerformerType.BAND
                ? publications.findForBand(targetId, PageRequest.of(page, size))
                : publications.findForMusician(targetId, PageRequest.of(page, size));
        var details = ids.isEmpty() ? Map.<UUID, Event>of() : (type == PerformerType.BAND
                ? publications.findBandDetails(ids.getContent(), targetId)
                : publications.findMusicianDetails(ids.getContent(), targetId)).stream()
                .collect(Collectors.toMap(Event::getId, Function.identity()));
        var bandEventIds = type == PerformerType.MUSICIAN ? details.values().stream()
                .filter(event -> event.getBand() != null).map(Event::getId).toList() : List.<UUID>of();
        // One bounded query for the page, not one query per band event/member choice.
        var choices = bandEventIds.isEmpty() ? Map.<UUID, EventMemberPublication>of()
                : memberPublications.findPageChoices(targetId, bandEventIds).stream()
                    .collect(Collectors.toMap(choice -> choice.getId().getEventId(), Function.identity()));
        // Missing/deleted events do not leak a stale publication through this private list.
        var result = new org.springframework.data.domain.PageImpl<>(ids.getContent().stream().map(details::get)
                .filter(Objects::nonNull).map(event -> dto(event, type, targetId,
                        type == PerformerType.MUSICIAN && event.getBand() != null
                            ? choices.getOrDefault(event.getId(), new EventMemberPublication(event.getId(), targetId)) : null))
                .toList(), ids.getPageable(), ids.getTotalElements());
        return PageResponse.from(result);
    }

    @Transactional
    public EventProfilePublicationDto update(UUID userId, UUID eventId, EventProfilePublicationUpdate update) {
        if (update == null || eventId == null || update.visible() == null || update.version() == null
                || update.version() < 0 || update.version() == Long.MAX_VALUE) throw invalid();
        validateTarget(userId, update.targetType(), update.targetId());
        // Membership and publication share the same band -> profile -> event lock order.
        // Do not hydrate an Event before its lock, avoiding a stale persistence-context copy.
        requireAuthority(userId, update.targetType(), update.targetId());
        if (!eligible(eventId, update.targetType(), update.targetId())) throw notFound();
        UUID bandId = publications.findBandId(eventId).orElse(null);
        if (bandId != null) bands.lockBandForUpdate(bandId).orElseThrow(this::notFound);
        lockTarget(userId, update.targetType(), update.targetId(), true);
        Event event = events.findByIdForUpdate(eventId).orElseThrow(this::notFound);
        if (!eligible(eventId, update.targetType(), update.targetId())) throw notFound();
        if (update.targetType() == PerformerType.MUSICIAN && event.getBand() != null) {
            EventMemberPublication current = memberPublication(event, update.targetId());
            if (shouldChange(current.isVisible(), current.getVersion(), update)) {
                current.setVisible(update.visible());
                current.setVersion(current.getVersion() + 1);
                memberPublications.saveAndFlush(current);
            }
        } else if (shouldChange(event.isProfileCalendarApproved(), event.getProfilePublicationVersion(), update)) {
            event.setProfileCalendarApproved(update.visible());
            event.setProfilePublicationVersion(event.getProfilePublicationVersion() + 1);
            events.saveAndFlush(event);
        }
        return dto(event, update.targetType(), update.targetId());
    }

    private boolean shouldChange(boolean visible, long version, EventProfilePublicationUpdate update) {
        if (version != update.version()) {
            if (version == update.version() + 1 && visible == update.visible()) return false;
            throw new SoundConnectException(ErrorType.MUSICIAN_CALENDAR_VERSION_CONFLICT);
        }
        return visible != update.visible();
    }

    private boolean eligible(UUID eventId, PerformerType type, UUID targetId) {
        return type == PerformerType.BAND ? publications.eligibleForBand(eventId, targetId)
                : publications.eligibleForMusician(eventId, targetId);
    }

    private void lockTarget(UUID userId, PerformerType type, UUID targetId, boolean write) {
        if (type == PerformerType.BAND) {
            (write ? bands.lockBandForUpdate(targetId) : bands.lockBandForRead(targetId)).orElseThrow(this::forbidden);
            bands.lockActiveFounder(targetId, userId).orElseThrow(this::forbidden);
        } else {
            (write ? profiles.lockOwnedProfileForUpdate(userId) : profiles.lockOwnedProfileForRead(userId))
                    .filter(targetId::equals).orElseThrow(this::forbidden);
        }
    }

    private EventMemberPublication memberPublication(Event event, UUID profileId) {
        return memberPublications.findById(new EventMemberPublication.Id(event.getId(), profileId))
                .orElseGet(() -> new EventMemberPublication(event.getId(), profileId));
    }

    private EventProfilePublicationDto dto(Event event, PerformerType type, UUID targetId) {
        EventMemberPublication member = type == PerformerType.MUSICIAN && event.getBand() != null
                ? memberPublication(event, targetId) : null;
        return dto(event, type, targetId, member);
    }

    private EventProfilePublicationDto dto(Event event, PerformerType type, UUID targetId, EventMemberPublication member) {
        String performer = event.getBand() != null ? event.getBand().getName() : event.getMusicianProfile().getUser().getUsername();
        return new EventProfilePublicationDto(event.getId(), type, targetId,
                member == null ? event.isProfileCalendarApproved() : member.isVisible(),
                member == null ? event.getProfilePublicationVersion() : member.getVersion(),
                event.getTitle(), event.getEventDate(), event.getStartTime(), event.getEndTime(),
                EventPosterResolver.resolve(event.getPosterImage(), media), event.getVenue().getId(), event.getVenue().getName(), performer);
    }

    private void validateTarget(UUID userId, PerformerType type, UUID targetId) {
        if (userId == null || targetId == null || type == null || type == PerformerType.MANUAL) throw invalid();
    }
    private SoundConnectException invalid() { return new SoundConnectException(ErrorType.INVALID_PARAMETER); }
    private SoundConnectException forbidden() { return new SoundConnectException(ErrorType.FORBIDDEN_ACCESS); }
    private SoundConnectException notFound() { return new SoundConnectException(ErrorType.EVENT_NOT_FOUND); }
}
