package com.berkayb.soundconnect.modules.event.service;

import com.berkayb.soundconnect.modules.event.dto.request.EventCreateRequestDto;
import com.berkayb.soundconnect.modules.event.enums.EventOrigin;
import com.berkayb.soundconnect.modules.event.performer.repository.EventPerformerRequestRepository;
import com.berkayb.soundconnect.modules.event.plan.EventPlanOccurrenceRepository;
import com.berkayb.soundconnect.modules.event.repository.EventRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** An owner-only draft keeps media identity; no previous participation decision is copied. */
@Service
@RequiredArgsConstructor
public class EventCopySourceService {
    private final EventRepository events;
    private final EventPerformerRequestRepository requests;
    private final EventPlanOccurrenceRepository occurrences;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public EventCreateRequestDto get(UUID actor, UUID eventId) {
        if (actor == null || eventId == null) throw notFound();
        var event = events.findById(eventId).orElseThrow(this::notFound);
        if (event.getEventOrigin() != EventOrigin.VENUE || event.getVenue() == null
                || event.getVenue().getOwner() == null || !actor.equals(event.getVenue().getOwner().getId())) {
            throw notFound();
        }
        UUID musicianId = event.getMusicianProfile() == null ? null : event.getMusicianProfile().getId();
        UUID bandId = event.getBand() == null ? null : event.getBand().getId();
        if (musicianId == null && bandId == null) {
            var request = requests.findByEvent_Id(eventId).orElse(null);
            if (request != null) {
                musicianId = request.getMusicianProfile() == null ? null : request.getMusicianProfile().getId();
                bandId = request.getBand() == null ? null : request.getBand().getId();
            } else {
                // Each occurrence retains its original selection, including a pending
                // plan invitation. Reading the current plan could retarget an old date.
                var occurrence = occurrences.findByEventId(eventId).orElse(null);
                if (occurrence != null) {
                    musicianId = occurrence.getOverrideMusicianProfileId();
                    bandId = occurrence.getOverrideBandId();
                }
            }
        }
        return new EventCreateRequestDto(event.getTitle(), event.getDescription(), event.getEventDate(),
                event.getStartTime(), event.getEndTime(), event.getPosterImage(), event.getVenue().getId(),
                musicianId, bandId, musicianId == null && bandId == null ? event.getManualPerformerName() : null);
    }

    private SoundConnectException notFound() { return new SoundConnectException(ErrorType.EVENT_NOT_FOUND); }
}
