package com.berkayb.soundconnect.modules.event.service;

import com.berkayb.soundconnect.modules.event.entity.Event;
import com.berkayb.soundconnect.modules.event.enums.EventOrigin;
import com.berkayb.soundconnect.modules.event.performer.entity.EventPerformerRequest;
import com.berkayb.soundconnect.modules.event.performer.repository.EventPerformerRequestRepository;
import com.berkayb.soundconnect.modules.event.repository.EventRepository;
import com.berkayb.soundconnect.modules.event.plan.EventPlanOccurrence;
import com.berkayb.soundconnect.modules.event.plan.EventPlanOccurrenceRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import com.berkayb.soundconnect.shared.exception.*;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class EventCopySourceServiceTest {
    private final EventRepository events = mock(EventRepository.class);
    private final EventPerformerRequestRepository requests = mock(EventPerformerRequestRepository.class);
    private final EventPlanOccurrenceRepository occurrences = mock(EventPlanOccurrenceRepository.class);
    private final EventCopySourceService service = new EventCopySourceService(events, requests, occurrences);
    private final UUID owner = UUID.randomUUID(), eventId = UUID.randomUUID();

    private Event source() {
        User user = new User(); user.setId(owner);
        Venue venue = new Venue(); venue.setId(UUID.randomUUID()); venue.setOwner(user);
        Event event = Event.builder().venue(venue).title("Cuma konseri")
                .posterImage(UUID.randomUUID().toString()).eventOrigin(EventOrigin.VENUE)
                .manualPerformerName("Sanatçı").build();
        event.setId(eventId);
        when(events.findById(eventId)).thenReturn(Optional.of(event));
        return event;
    }

    @Test
    void preservesRawPosterIdentityAndPendingSelectionWithoutCopyingApproval() {
        Event source = source();
        var profile = new MusicianProfile(); profile.setId(UUID.randomUUID());
        when(requests.findByEvent_Id(eventId)).thenReturn(Optional.of(
                EventPerformerRequest.builder().musicianProfile(profile).build()));

        var draft = service.get(owner, eventId);

        assertThat(draft.posterImage()).isEqualTo(source.getPosterImage());
        assertThat(draft.musicianProfileId()).isEqualTo(profile.getId());
        assertThat(draft.manualPerformerName()).isNull();
        assertThat(source.isProfileCalendarApproved()).isFalse();
        verify(events, never()).save(any());
    }

    @Test
    void otherVenueCannotReadPrivatePerformerSelection() {
        source();
        assertThatThrownBy(() -> service.get(UUID.randomUUID(), eventId))
                .isInstanceOfSatisfying(SoundConnectException.class,
                        failure -> assertThat(failure.getErrorType()).isEqualTo(ErrorType.EVENT_NOT_FOUND));
        verifyNoInteractions(requests, occurrences);
    }

    @Test
    void plainNameRemainsManualAndLinkedPerformerDoesNotReadInvitation() {
        Event event = source();
        when(requests.findByEvent_Id(eventId)).thenReturn(Optional.empty());
        assertThat(service.get(owner, eventId).manualPerformerName()).isEqualTo("Sanatçı");
        clearInvocations(requests);
        var profile = new MusicianProfile(); profile.setId(UUID.randomUUID());
        event.setMusicianProfile(profile);
        assertThat(service.get(owner, eventId).musicianProfileId()).isEqualTo(profile.getId());
        verifyNoInteractions(requests);
    }

    @Test
    void pendingPlanDateUsesItsOwnSelectionWithoutBorrowingCurrentPlanOrApproval() {
        source();
        UUID originalBand = UUID.randomUUID();
        EventPlanOccurrence occurrence = new EventPlanOccurrence();
        occurrence.setOverrideBandId(originalBand);
        when(occurrences.findByEventId(eventId)).thenReturn(Optional.of(occurrence));

        var draft = service.get(owner, eventId);

        assertThat(draft.bandId()).isEqualTo(originalBand);
        assertThat(draft.musicianProfileId()).isNull();
        assertThat(draft.manualPerformerName()).isNull();
        verify(events, never()).save(any());
    }
}
