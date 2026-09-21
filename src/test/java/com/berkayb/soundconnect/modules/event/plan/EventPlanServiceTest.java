package com.berkayb.soundconnect.modules.event.plan;

import com.berkayb.soundconnect.modules.event.entity.Event;
import com.berkayb.soundconnect.modules.event.enums.EventPerformerApprovalStatus;
import com.berkayb.soundconnect.modules.event.mapper.EventMapper;
import com.berkayb.soundconnect.modules.event.repository.EventRepository;
import com.berkayb.soundconnect.modules.event.performer.service.EventPerformerRequestService;
import com.berkayb.soundconnect.modules.event.performer.outbox.EventPerformerNotificationOutboxPublisher;
import com.berkayb.soundconnect.modules.event.support.EventScheduleClock;
import com.berkayb.soundconnect.modules.media.repository.MediaAssetRepository;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.repository.MusicianProfileRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository.*;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.support.BandRepresentationPolicy;
import com.berkayb.soundconnect.modules.profile.VenueProfile.repository.VenueProfileRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import com.berkayb.soundconnect.modules.venue.enums.VenueStatus;
import com.berkayb.soundconnect.modules.venue.repository.VenueRepository;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@ExtendWith(MockitoExtension.class)
class EventPlanServiceTest {
    @Mock EventPlanRepository plans;
    @Mock EventPlanOccurrenceRepository occurrences;
    @Mock EventRepository events;
    @Mock VenueRepository venues;
    @Mock MusicianProfileRepository musicians;
    @Mock BandRepository bands;
    @Mock BandMemberRepository members;
    @Mock BandRepresentationPolicy representation;
    @Mock MediaAssetRepository assets;
    @Mock VenueProfileRepository venueProfiles;
    @Mock MediaAssetService media;
    @Mock EventMapper mapper;
    @Mock EventPerformerRequestService requests;
    @Mock EventPerformerNotificationOutboxPublisher notifications;
    @Mock EventScheduleClock clock;
    @Mock ObjectMapper json;
    @InjectMocks EventPlanService service;
    final UUID id=UUID.randomUUID(),ownerId=UUID.randomUUID(),venueId=UUID.randomUUID(),musicianId=UUID.randomUUID();
    final LocalDate date=LocalDate.of(2026,9,22);
    final Instant now=Instant.parse("2026-09-21T09:00:00Z");

    @Test void posterReferencesUseCanonicalUuidTextForMediaDeletionFencing() {
        EventPlanTemplate normalized = EventPlanRules.normalizeTemplate(new EventPlanTemplate("Concert", null,
                LocalTime.of(20,0), null, "1-1-1-1-1", null, null, null));
        assertThat(normalized.posterImage()).isEqualTo(UUID.fromString("1-1-1-1-1").toString());
    }

    @Test void updatePreviewSeparatesImmutableDatesWithoutReadingOrWritingEventEntities() {
        EventPlan plan = plan();
        when(plans.findById(id)).thenReturn(Optional.of(plan));
        when(venues.findById(venueId)).thenReturn(Optional.of(venue()));
        when(clock.instant()).thenReturn(now);
        var definition = previewDefinition(venueId);
        when(occurrences.findPreviewWindow(id, date, date.plusDays(27))).thenReturn(List.of(
                new EventPlanPreviewOccurrence(date, date, EventPlanOccurrenceStatus.SKIPPED, null, null),
                new EventPlanPreviewOccurrence(date.plusDays(1), date.plusDays(8), EventPlanOccurrenceStatus.OVERRIDDEN, UUID.randomUUID(), LocalTime.of(20,0)),
                new EventPlanPreviewOccurrence(date.plusDays(2), date.plusDays(2), EventPlanOccurrenceStatus.CANCELLED, null, null),
                new EventPlanPreviewOccurrence(date.plusDays(3), date.plusDays(3), EventPlanOccurrenceStatus.GENERATED, null, null),
                new EventPlanPreviewOccurrence(date.plusDays(4), date.plusDays(4), EventPlanOccurrenceStatus.GENERATED, UUID.randomUUID(), LocalTime.of(20,0)),
                new EventPlanPreviewOccurrence(date.plusDays(5), date.minusDays(1), EventPlanOccurrenceStatus.GENERATED, UUID.randomUUID(), LocalTime.of(10,0)),
                new EventPlanPreviewOccurrence(date.plusDays(30), date.plusDays(7), EventPlanOccurrenceStatus.OVERRIDDEN, UUID.randomUUID(), LocalTime.of(20,0))));

        EventPlanPreview result = service.previewUpdate(ownerId, id, new EventPlanUpdateRequest(0L, definition));

        assertThat(result.dates()).hasSize(23).contains(date.plusDays(4), date.plusDays(7), date.plusDays(8))
                .doesNotContain(date, date.plusDays(1), date.plusDays(2), date.plusDays(3), date.plusDays(5));
        assertThat(result.preservedDates()).containsExactly(
                new EventPlanPreservedDate(date, date, EventPlanPreservationStatus.SKIPPED),
                new EventPlanPreservedDate(date.plusDays(1), date.plusDays(8), EventPlanPreservationStatus.OVERRIDDEN),
                new EventPlanPreservedDate(date.plusDays(2), date.plusDays(2), EventPlanPreservationStatus.CANCELLED),
                new EventPlanPreservedDate(date.plusDays(3), date.plusDays(3), EventPlanPreservationStatus.CANCELLED),
                new EventPlanPreservedDate(date.plusDays(5), date.minusDays(1), EventPlanPreservationStatus.STARTED),
                new EventPlanPreservedDate(date.plusDays(30), date.plusDays(7), EventPlanPreservationStatus.OVERRIDDEN));
        assertThat(result.serverNow()).isEqualTo(now);
        assertThat(plan.getVersion()).isZero();
        verify(occurrences).findPreviewWindow(id, date, date.plusDays(27));
        verifyNoMoreInteractions(occurrences);
        verify(plans, never()).findByIdForUpdate(any());
        verifyNoInteractions(events, notifications, requests, assets);
    }

    @Test void updatePreviewRejectsForeignOwnersVenueSubstitutionStaleRevisionAndStoppedPlans() {
        EventPlan plan = plan();
        when(plans.findById(id)).thenReturn(Optional.of(plan));
        assertThatThrownBy(() -> service.previewUpdate(UUID.randomUUID(), id,
                new EventPlanUpdateRequest(0L, previewDefinition(venueId))))
                .isInstanceOfSatisfying(SoundConnectException.class,
                        error -> assertThat(error.getErrorType()).isEqualTo(ErrorType.EVENT_NOT_FOUND));
        when(venues.findById(venueId)).thenReturn(Optional.of(venue()));
        assertThatThrownBy(() -> service.previewUpdate(ownerId, id,
                new EventPlanUpdateRequest(0L, previewDefinition(UUID.randomUUID()))))
                .isInstanceOfSatisfying(SoundConnectException.class,
                        error -> assertThat(error.getErrorType()).isEqualTo(ErrorType.INVALID_PARAMETER));
        assertThatThrownBy(() -> service.previewUpdate(ownerId, id,
                new EventPlanUpdateRequest(1L, previewDefinition(venueId))))
                .isInstanceOfSatisfying(SoundConnectException.class,
                        error -> assertThat(error.getErrorType()).isEqualTo(ErrorType.MUSICIAN_CALENDAR_VERSION_CONFLICT));
        assertThatThrownBy(() -> service.previewUpdate(ownerId, id,
                new EventPlanUpdateRequest(null, previewDefinition(venueId))))
                .isInstanceOfSatisfying(SoundConnectException.class,
                        error -> assertThat(error.getErrorType()).isEqualTo(ErrorType.INVALID_PARAMETER));
        plan.setStatus(EventPlanStatus.STOPPED);
        assertThatThrownBy(() -> service.previewUpdate(ownerId, id,
                new EventPlanUpdateRequest(0L, previewDefinition(venueId))))
                .isInstanceOfSatisfying(SoundConnectException.class,
                        error -> assertThat(error.getErrorType()).isEqualTo(ErrorType.EVENT_PERFORMER_REQUEST_FINALIZED));
        verifyNoInteractions(occurrences, events, notifications, requests, clock);
    }

    @Test void createPreviewKeepsItsOriginalDatesAndHasNoPreservedLedgerDates() {
        when(venues.findById(venueId)).thenReturn(Optional.of(venue()));
        when(clock.instant()).thenReturn(now);
        EventPlanPreview result = service.preview(ownerId, previewDefinition(venueId));
        assertThat(result.dates()).hasSize(28).startsWith(date).endsWith(date.plusDays(27));
        assertThat(result.preservedDates()).isEmpty();
        verifyNoInteractions(plans, occurrences, events, notifications, requests);
    }

    private EventPlanDefinition previewDefinition(UUID venue) {
        return new EventPlanDefinition(venue, date, null, List.of(1,2,3,4,5,6,7), List.of(),
                new EventPlanTemplate("Preview", null, LocalTime.of(20,0), null, null, null, null, "Performer"));
    }

    @Test void deletedBandStopsGenerationWithoutRepeatedPoisonOrAnyNewEvents() {
        EventPlan p=plan();UUID band=UUID.randomUUID();p.setBandId(band);p.setMusicianProfileId(null);p.setConsentStatus(EventPlanConsentStatus.ACCEPTED);p.setShowOnProfile(true);
        when(plans.findAuthority(id)).thenReturn(Optional.of(new EventPlanAuthority(ownerId,venueId,band,null)));
        when(plans.findByIdForUpdate(id)).thenReturn(Optional.of(p));when(venues.findById(venueId)).thenReturn(Optional.of(venue()));
        service.generate(id);
        assertThat(p.getStatus()).isEqualTo(EventPlanStatus.STOPPED);
        assertThat(p.getConsentStatus()).isEqualTo(EventPlanConsentStatus.WITHDRAWN);
        assertThat(p.isShowOnProfile()).isFalse();
        var order=inOrder(plans,bands);order.verify(plans).findAuthority(id);order.verify(bands).findByIdForUpdate(band);order.verify(plans).findByIdForUpdate(id);
        verifyNoInteractions(events,occurrences,notifications);
    }

    @Test void changedTargetAfterPreflightCannotUseAnUnheldBandFence() {
        EventPlan p=plan();UUID before=UUID.randomUUID(),after=UUID.randomUUID();p.setBandId(after);
        when(plans.findAuthority(id)).thenReturn(Optional.of(new EventPlanAuthority(ownerId,venueId,before,musicianId)));
        when(plans.findByIdForUpdate(id)).thenReturn(Optional.of(p));
        assertThatThrownBy(() -> service.generate(id)).isInstanceOf(SoundConnectException.class);
        verifyNoInteractions(events,venues,occurrences,notifications);
    }

    @Test void titleOnlyOverrideOfPendingSeriesCreatesAnAnswerableStandaloneInvitation() {
        EventPlan p=plan();p.setConsentStatus(EventPlanConsentStatus.PENDING);
        Venue v=venue();MusicianProfile m=musician();
        Event e=Event.builder().title("Weekly music").eventDate(date).startTime(LocalTime.of(20,0)).venue(v)
                .manualPerformerName("musician").performerApprovalStatus(EventPerformerApprovalStatus.PENDING).build();e.setId(UUID.randomUUID());
        EventPlanOccurrence o=new EventPlanOccurrence(id,date);o.setEventId(e.getId());o.setStatus(EventPlanOccurrenceStatus.GENERATED);o.setOverrideMusicianProfileId(musicianId);
        when(plans.findAuthority(id)).thenReturn(Optional.of(new EventPlanAuthority(ownerId,venueId,null,musicianId)));
        when(plans.findByIdForUpdate(id)).thenReturn(Optional.of(p));when(venues.findById(venueId)).thenReturn(Optional.of(v));
        when(occurrences.findById(new EventPlanOccurrence.Id(id,date))).thenReturn(Optional.of(o));
        when(events.findByIdForUpdate(e.getId())).thenReturn(Optional.of(e));
        when(musicians.findById(musicianId)).thenReturn(Optional.of(m));
        when(clock.instant()).thenReturn(now);when(clock.startsAt(e)).thenCallRealMethod();
        service.override(ownerId,id,date,new EventPlanOverrideRequest(0L,date,
                new EventPlanTemplate("Special title",null,LocalTime.of(20,0),null,null,musicianId,null,null)));
        assertThat(e.getId()).isEqualTo(o.getEventId());assertThat(o.getStatus()).isEqualTo(EventPlanOccurrenceStatus.OVERRIDDEN);
        assertThat(e.getPerformerApprovalStatus()).isEqualTo(EventPerformerApprovalStatus.PENDING);
        verify(requests).createPendingRequest(ownerId,e,m,null);verify(requests).deleteForEvent(e.getId());
        assertThat(e.getProfilePublicationVersion()).isEqualTo(1);
    }

    @Test void acceptedRetryNeverReappliesPublicationOrSendsAnotherNotification() {
        EventPlan p=plan();p.setConsentStatus(EventPlanConsentStatus.ACCEPTED);p.setAcceptedPublication(true);p.setShowOnProfile(true);p.setVersion(1);
        when(plans.findAuthority(id)).thenReturn(Optional.of(new EventPlanAuthority(ownerId,venueId,null,musicianId)));
        when(plans.findByIdForUpdate(id)).thenReturn(Optional.of(p));when(musicians.findById(musicianId)).thenReturn(Optional.of(musician()));
        when(venues.findById(venueId)).thenReturn(Optional.of(venue()));when(clock.instant()).thenReturn(now);
        service.decide(ownerId,id,new EventPlanDecisionRequest(0L,EventPlanDecision.ACCEPT,true));
        verifyNoInteractions(events,occurrences,requests,notifications);assertThat(p.getVersion()).isEqualTo(1);
    }

    @Test void stoppedWithdrawalChecksOnlyScalarsBeforeLockAndAdvancesTheCurrentPublicationRevision() {
        EventPlan p=plan();p.setStatus(EventPlanStatus.STOPPED);p.setConsentStatus(EventPlanConsentStatus.ACCEPTED);
        p.setAcceptedPublication(true);p.setShowOnProfile(true);p.setVersion(1);
        MusicianProfile musician=musician();
        Event event=Event.builder().title("Weekly music").eventDate(date).startTime(LocalTime.of(20,0))
                .venue(venue()).musicianProfile(musician).performerApprovalStatus(EventPerformerApprovalStatus.APPROVED)
                .profileCalendarApproved(false).profilePublicationVersion(4).build();
        event.setId(UUID.randomUUID());
        EventPlanOccurrence occurrence=new EventPlanOccurrence(id,date);
        occurrence.setEventId(event.getId());occurrence.setStatus(EventPlanOccurrenceStatus.GENERATED);
        when(plans.findAuthority(id)).thenReturn(Optional.of(new EventPlanAuthority(ownerId,venueId,null,musicianId)));
        when(plans.findByIdForUpdate(id)).thenReturn(Optional.of(p));
        when(musicians.findById(musicianId)).thenReturn(Optional.of(musician));
        when(venues.findById(venueId)).thenReturn(Optional.of(venue()));
        when(clock.instant()).thenReturn(now);when(clock.startsAt(event)).thenCallRealMethod();
        when(occurrences.findGeneratedStarts(id,LocalDate.of(2026,9,21)))
                .thenReturn(List.of(new EventPlanStart(date,LocalTime.of(20,0))));
        when(occurrences.findByIdPlanIdAndEventDateGreaterThanEqualOrderByIdScheduledDate(id,LocalDate.of(2026,9,21)))
                .thenReturn(List.of(occurrence));
        when(events.findByIdForUpdate(event.getId())).thenReturn(Optional.of(event));

        service.decide(ownerId,id,new EventPlanDecisionRequest(1L,EventPlanDecision.WITHDRAW,null));

        verify(events,never()).findById(any());
        assertThat(event.getProfilePublicationVersion()).isEqualTo(5);
        assertThat(event.getPerformerApprovalStatus()).isEqualTo(EventPerformerApprovalStatus.REJECTED);
        assertThat(event.getMusicianProfile()).isNull();
        assertThat(p.getConsentStatus()).isEqualTo(EventPlanConsentStatus.WITHDRAWN);
    }

    EventPlan plan() {
        EventPlan p=new EventPlan();p.setId(id);p.setOrganizerUserId(ownerId);p.setVenueId(venueId);p.setStartDate(date);
        p.setWeekdayMask(127);p.setTitle("Weekly music");p.setStartTime(LocalTime.of(20,0));p.setMusicianProfileId(musicianId);
        p.setPerformerNameSnapshot("musician");p.setStatus(EventPlanStatus.ACTIVE);p.setConsentStatus(EventPlanConsentStatus.PENDING);return p;
    }
    Venue venue() { Venue v=new Venue();v.setId(venueId);v.setName("Venue");v.setStatus(VenueStatus.APPROVED);v.setOwner(user());return v; }
    User user() { User u=new User();u.setId(ownerId);u.setUsername("musician");u.setStatus(UserStatus.ACTIVE);u.setEmailVerified(true);return u; }
    MusicianProfile musician() { MusicianProfile m=new MusicianProfile();m.setId(musicianId);m.setUser(user());return m; }
}
