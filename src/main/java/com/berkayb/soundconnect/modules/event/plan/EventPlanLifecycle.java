package com.berkayb.soundconnect.modules.event.plan;

import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository.BandRepository;
import com.berkayb.soundconnect.shared.exception.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;
import java.util.Objects;
import java.util.UUID;

/** Normal event deletion takes the recurrence fence before taking the event lock. */
@Component @RequiredArgsConstructor
public class EventPlanLifecycle {
    private final EventPlanOccurrenceRepository occurrences;
    private final EventPlanRepository plans;
    private final BandRepository bands;

    @Transactional(propagation=Propagation.MANDATORY)
    public void lockForEventDeletion(UUID eventId) {
        UUID planId=occurrences.findPlanIdByEventId(eventId).orElse(null);
        if(planId==null)return;
        EventPlanAuthority a=plans.findAuthority(planId).orElseThrow(() -> new SoundConnectException(ErrorType.EVENT_NOT_FOUND));
        if(a.bandId()!=null)bands.findByIdForUpdate(a.bandId());
        EventPlan p=plans.findByIdForUpdate(planId).orElseThrow(() -> new SoundConnectException(ErrorType.EVENT_NOT_FOUND));
        if(!Objects.equals(a.bandId(),p.getBandId()))throw new SoundConnectException(ErrorType.MUSICIAN_CALENDAR_VERSION_CONFLICT);
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public void recordDeletion(UUID eventId) {
        occurrences.findByEventId(eventId).ifPresent(o -> {
            o.setEventId(null);o.setStatus(EventPlanOccurrenceStatus.CANCELLED);occurrences.saveAndFlush(o);
        });
    }
}
