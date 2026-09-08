package com.berkayb.soundconnect.modules.event.performer.service;

import com.berkayb.soundconnect.modules.event.entity.Event;
import com.berkayb.soundconnect.modules.event.enums.*;
import com.berkayb.soundconnect.modules.event.mapper.EventMapper;
import com.berkayb.soundconnect.modules.event.performer.entity.EventPerformerRequest;
import com.berkayb.soundconnect.modules.event.performer.enums.*;
import com.berkayb.soundconnect.modules.event.performer.outbox.EventPerformerNotificationOutboxPublisher;
import com.berkayb.soundconnect.modules.event.performer.repository.EventPerformerRequestRepository;
import com.berkayb.soundconnect.modules.event.repository.EventRepository;
import com.berkayb.soundconnect.modules.event.service.EventService;
import com.berkayb.soundconnect.modules.event.service.EventServiceImpl;
import com.berkayb.soundconnect.modules.event.support.EventShareUrlBuilder;
import com.berkayb.soundconnect.modules.event.support.EventScheduleClock;
import com.berkayb.soundconnect.modules.location.entity.*;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.calendar.BandCalendarService;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.calendar.BandCalendarEventRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.*;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.*;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.support.BandRepresentationPolicy;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.calendar.dto.MusicianCalendarSettingsUpdate;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.calendar.repository.MusicianCalendarEventRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.calendar.service.MusicianCalendarService;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.service.MusicianProfileService;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.modules.user.support.UserEntityFinder;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import com.berkayb.soundconnect.modules.venue.enums.VenueStatus;
import com.berkayb.soundconnect.modules.venue.support.VenueEntityFinder;
import com.berkayb.soundconnect.shared.exception.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({EventPerformerRequestServiceImpl.class, BandRepresentationPolicy.class, MusicianCalendarService.class,
        BandCalendarService.class, EventMapper.class, EventServiceImpl.class})
@ActiveProfiles("test") @Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class VenueOnlyEventFlowPostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("venue_only_rollback").withUsername("soundconnect").withPassword("soundconnect");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername); registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }
    @Autowired EventPerformerRequestService invitations;
    @Autowired EventPerformerRequestRepository requests;
    @Autowired EventRepository events;
    @Autowired EventService eventService;
    @Autowired MusicianCalendarService musicianCalendar;
    @Autowired MusicianCalendarEventRepository musicianEvents;
    @Autowired BandCalendarService bandCalendar;
    @Autowired BandCalendarEventRepository bandEvents;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean EventPerformerNotificationOutboxPublisher notifications;
    @MockitoBean MediaAssetService media;
    @MockitoBean EventShareUrlBuilder shares;
    @MockitoBean VenueEntityFinder venueFinder;
    @MockitoBean UserEntityFinder userFinder;
    @MockitoBean MusicianProfileService profiles;
    @MockitoBean EventScheduleClock scheduleClock;
    private final LocalDate date = LocalDate.of(2026, 9, 20);

    @BeforeEach void resetMocks() {
        reset(notifications);
        when(scheduleClock.instant()).thenReturn(Instant.parse("2026-09-06T09:00:00Z"));
        when(scheduleClock.localNow()).thenReturn(LocalDateTime.of(2026, 9, 6, 12, 0));
        when(scheduleClock.startsAt(any())).thenCallRealMethod();
    }

    @ParameterizedTest @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void rejectedInvitationCanBeReconsideredWithPrivatePagingAndSingleNotification(boolean band, boolean connected) {
        Fixture f = fixture(connected);
        UUID id = band ? f.bandRequest() : f.personalRequest();
        PerformerType type = band ? PerformerType.BAND : PerformerType.MUSICIAN;
        UUID target = band ? f.band() : f.profile();
        invitations.reject(f.actor(), id);
        var rejected = invitations.getMine(f.actor(), EventPerformerRequestStatus.REJECTED, type, target, 0, 1);
        assertThat(rejected.totalElements()).isEqualTo(1);
        assertThat(rejected.content()).extracting(item -> item.canReconsider()).containsExactly(true);
        assertThatThrownBy(() -> invitations.accept(f.actor(), id, true))
                .isInstanceOfSatisfying(SoundConnectException.class, error -> assertThat(error.getErrorType())
                        .isEqualTo(ErrorType.EVENT_PERFORMER_REQUEST_FINALIZED));
        var accepted = invitations.reconsider(f.actor(), id, true);
        assertThat(accepted.status()).isEqualTo(EventPerformerRequestStatus.ACCEPTED);
        assertThat(accepted.profileCalendarApproved()).isTrue();
        assertThat(invitations.getMine(f.actor(), EventPerformerRequestStatus.REJECTED, type, target, 0, 1).totalElements()).isZero();
        tx().executeWithoutResult(status -> {
            Event event = em.find(EventPerformerRequest.class, id).getEvent();
            assertThat(event.getPerformerApprovalStatus()).isEqualTo(EventPerformerApprovalStatus.APPROVED);
            assertThat(band ? event.getBand().getId() : event.getMusicianProfile().getId()).isEqualTo(target);
            event.setProfileCalendarApproved(false);
        });
        when(scheduleClock.instant()).thenReturn(accepted.eventStartsAt().plusSeconds(1));
        assertThat(invitations.reconsider(f.actor(), id, true).profileCalendarApproved()).isFalse();
        verify(notifications, times(2)).enqueueAll(any()); // initial rejection + later acceptance, never retry
    }

    @ParameterizedTest @ValueSource(booleans = {false,true})
    void concurrentReconsiderationCommitsOnlyOnePublicationChoice(boolean band) throws Exception {
        Fixture f = fixture(false); UUID id = band ? f.bandRequest() : f.personalRequest();
        invitations.reject(f.actor(), id);
        reset(notifications);
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { start.await(); return invitations.reconsider(f.actor(), id, true); });
            var second = pool.submit(() -> { start.await(); return invitations.reconsider(f.actor(), id, true); });
            start.countDown();
            assertThat(first.get(15, TimeUnit.SECONDS).status()).isEqualTo(EventPerformerRequestStatus.ACCEPTED);
            assertThat(second.get(15, TimeUnit.SECONDS).status()).isEqualTo(EventPerformerRequestStatus.ACCEPTED);
        }
        verify(notifications).enqueueAll(any());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void conflictingConcurrentReconsiderationCannotOverwriteTheCommittedChoice(boolean band) throws Exception {
        Fixture f = fixture(false); UUID id = band ? f.bandRequest() : f.personalRequest();
        invitations.reject(f.actor(), id);
        reset(notifications);
        CountDownLatch start = new CountDownLatch(1);
        List<AcceptAttempt> attempts;
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { assertThat(start.await(5, TimeUnit.SECONDS)).isTrue(); return reconsiderSelection(f.actor(), id, false); });
            var second = pool.submit(() -> { assertThat(start.await(5, TimeUnit.SECONDS)).isTrue(); return reconsiderSelection(f.actor(), id, true); });
            start.countDown();
            attempts = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
        }
        assertThat(attempts.stream().filter(AcceptAttempt::accepted)).hasSize(1);
        assertThat(attempts.stream().filter(attempt -> !attempt.accepted())).hasSize(1);
        boolean chosen = attempts.stream().filter(AcceptAttempt::accepted).findFirst().orElseThrow().showOnProfile();
        tx().executeWithoutResult(status -> {
            EventPerformerRequest saved = em.find(EventPerformerRequest.class, id);
            assertThat(saved.getStatus()).isEqualTo(EventPerformerRequestStatus.ACCEPTED);
            assertThat(saved.getVersion()).isEqualTo(2);
            assertThat(saved.getAcceptedProfilePublication()).isEqualTo(chosen);
            assertThat(saved.getEvent().isProfileCalendarApproved()).isEqualTo(chosen);
        });
        verify(notifications).enqueueAll(any());
    }

    private AcceptAttempt reconsiderSelection(UUID actor, UUID request, boolean choice) {
        try {
            invitations.reconsider(actor, request, choice);
            return new AcceptAttempt(true, choice);
        } catch (SoundConnectException exception) {
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.EVENT_PERFORMER_REQUEST_FINALIZED);
            return new AcceptAttempt(false, choice);
        }
    }

    @ParameterizedTest @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void failedReconsiderNotificationPersistencePreservesThePreviousRejection(boolean band, boolean connected) {
        Fixture f = fixture(connected); UUID id = band ? f.bandRequest() : f.personalRequest();
        var rejection = invitations.reject(f.actor(), id);
        reset(notifications);
        doThrow(new IllegalStateException("Simulated reconsider outbox persistence failure"))
                .when(notifications).enqueueAll(any());
        assertThatThrownBy(() -> invitations.reconsider(f.actor(), id, true))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("outbox persistence failure");
        tx().executeWithoutResult(status -> {
            EventPerformerRequest saved = em.find(EventPerformerRequest.class, id);
            assertThat(saved.getStatus()).isEqualTo(EventPerformerRequestStatus.REJECTED);
            assertThat(saved.getVersion()).isEqualTo(1);
            assertThat(saved.getAcceptedProfilePublication()).isNull();
            assertThat(saved.getDecidedAt()).isEqualTo(rejection.decidedAt());
            assertThat(saved.getDecidedByUserId()).isEqualTo(f.actor());
            Event event = saved.getEvent();
            assertThat(event.isProfileCalendarApproved()).isFalse();
            assertThat(event.getProfilePublicationVersion()).isZero();
            assertThat(event.getPerformerApprovalStatus()).isEqualTo(connected
                    ? EventPerformerApprovalStatus.APPROVED : EventPerformerApprovalStatus.REJECTED);
            assertThat(event.getMusicianProfile() != null || event.getBand() != null).isEqualTo(connected);
        });
    }

    @ParameterizedTest @ValueSource(booleans = {false,true})
    void rejectedInvitationAuthorityIsRecheckedAndCannotBeUsedByBandMemberOrStranger(boolean band) {
        Fixture f = fixture(false); UUID id = band ? f.bandRequest() : f.personalRequest();
        invitations.reject(f.actor(), id);
        MemberFixture member = activeMember(f.band());
        for (UUID actor : List.of(UUID.randomUUID(), member.user())) {
            assertThatThrownBy(() -> invitations.reconsider(actor, id, true))
                    .isInstanceOfSatisfying(SoundConnectException.class, error -> assertThat(error.getErrorType())
                            .isEqualTo(ErrorType.EVENT_PERFORMER_REQUEST_NOT_FOUND));
        }
        assertThat(requests.findById(id).orElseThrow().getStatus()).isEqualTo(EventPerformerRequestStatus.REJECTED);
    }

    @ParameterizedTest @ValueSource(booleans = {false,true})
    void expiredPendingRowsStayPendingAndExpiredRejectedRowsStayReadOnly(boolean band) {
        Fixture f = fixture(false); UUID id = band ? f.bandRequest() : f.personalRequest();
        PerformerType type = band ? PerformerType.BAND : PerformerType.MUSICIAN;
        UUID target = band ? f.band() : f.profile();
        Instant start = date.atTime(20,0).atZone(EventScheduleClock.ZONE).toInstant();
        when(scheduleClock.instant()).thenReturn(start);
        var pending = invitations.getMine(f.actor(), EventPerformerRequestStatus.PENDING, type, target, 0, 1);
        assertThat(pending.totalElements()).isEqualTo(1);
        assertThat(pending.content().getFirst().expired()).isTrue();
        assertThat(pending.content().getFirst().decisionAllowed()).isFalse();
        assertThatThrownBy(() -> invitations.accept(f.actor(), id, true))
                .isInstanceOfSatisfying(SoundConnectException.class, error -> assertThat(error.getErrorType())
                        .isEqualTo(ErrorType.EVENT_PERFORMER_REQUEST_EXPIRED));
        assertThatThrownBy(() -> invitations.reject(f.actor(), id))
                .isInstanceOfSatisfying(SoundConnectException.class, error -> assertThat(error.getErrorType())
                        .isEqualTo(ErrorType.EVENT_PERFORMER_REQUEST_EXPIRED));
        when(scheduleClock.instant()).thenReturn(start.minusNanos(1));
        invitations.reject(f.actor(), id);
        when(scheduleClock.instant()).thenReturn(start);
        assertThatThrownBy(() -> invitations.reconsider(f.actor(), id, true))
                .isInstanceOfSatisfying(SoundConnectException.class, error -> assertThat(error.getErrorType())
                        .isEqualTo(ErrorType.EVENT_PERFORMER_REQUEST_EXPIRED));
        assertThat(invitations.reject(f.actor(), id).expired()).isTrue();
        assertThat(invitations.getMine(f.actor(), EventPerformerRequestStatus.REJECTED, type, target, 0, 1)
                .content().getFirst().canReconsider()).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"false,false,false", "false,false,true", "false,true,false", "false,true,true",
            "true,false,false", "true,false,true", "true,true,false", "true,true,true"})
    void calendarOffNeverBlocksInvitationsOrDecisionsAndConsentNeverEnablesEitherMaster(
            boolean band, boolean connected, boolean accepted) {
        Fixture f = fixture(connected);
        UUID requestId = band ? f.bandRequest() : f.personalRequest();
        assertThat(musicianCalendar.getSettings(f.actor()).visible()).isFalse();
        assertThat(bandCalendar.getSettings(f.band(), f.actor()).visible()).isFalse();
        var scoped = invitations.getMine(f.actor(), EventPerformerRequestStatus.PENDING,
                band ? PerformerType.BAND : PerformerType.MUSICIAN, band ? f.band() : f.profile(), 0, 20);
        assertThat(scoped.content()).extracting(item -> item.id()).containsExactly(requestId);
        for (EventPerformerRequestStatus filter : new EventPerformerRequestStatus[]{null, EventPerformerRequestStatus.PENDING}) {
            var aggregate = invitations.getMine(f.actor(), filter, null, null, 0, 1);
            assertThat(aggregate.totalElements()).isEqualTo(2);
            assertThat(aggregate.content()).hasSize(1);
        }
        var result = accepted ? invitations.accept(f.actor(), requestId) : invitations.reject(f.actor(), requestId);
        assertThat(result.status()).isEqualTo(accepted ? EventPerformerRequestStatus.ACCEPTED : EventPerformerRequestStatus.REJECTED);
        assertThat(musicianCalendar.getSettings(f.actor()).visible()).isFalse();
        assertThat(bandCalendar.getSettings(f.band(), f.actor()).visible()).isFalse();
        tx().executeWithoutResult(status -> {
            Event event = em.find(EventPerformerRequest.class, requestId).getEvent();
            assertThat(event.isProfileCalendarApproved()).isEqualTo(accepted && connected);
            assertThat(event.getBand() != null || event.getMusicianProfile() != null).isEqualTo(connected || accepted);
            assertThat(event.getEventOrigin()).isEqualTo(EventOrigin.VENUE);
        });
        verify(notifications).enqueueAll(any());
        musicianCalendar.updateSettings(f.actor(), new MusicianCalendarSettingsUpdate(true, 0L));
        var afterPersonalOptIn = musicianCalendar.getCalendar(f.profile(), date, date, 0, 20).events();
        if (accepted && connected && !band) assertThat(afterPersonalOptIn).hasSize(1);
        else assertThat(afterPersonalOptIn).isEmpty(); // Band switch remains a veto even for an enabled musician.
    }

    @Test void bothDisabledMastersStillDoNotGrantOtherActorsDecisionAuthority() {
        Fixture f = fixture(false);
        for (UUID id : List.of(f.personalRequest(), f.bandRequest())) {
            assertThatThrownBy(() -> invitations.accept(UUID.randomUUID(), id))
                    .isInstanceOfSatisfying(SoundConnectException.class, error -> assertThat(error.getErrorType())
                            .isEqualTo(ErrorType.EVENT_PERFORMER_REQUEST_NOT_FOUND));
            assertThat(requests.findById(id).orElseThrow().getStatus()).isEqualTo(EventPerformerRequestStatus.PENDING);
        }
        verifyNoInteractions(notifications);
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void acceptedWithoutProfileOptInIsHiddenEvenWhenEveryMasterIsEnabledBeforeOrAfterDecision(
            boolean band, boolean enableBeforeDecision) {
        Fixture f = fixture(false);
        MemberFixture member = activeMember(f.band());
        UUID requestId = band ? f.bandRequest() : f.personalRequest();
        if (enableBeforeDecision) enableAllMasters(f, member);

        var accepted = invitations.accept(f.actor(), requestId, false);
        assertThat(accepted.status()).isEqualTo(EventPerformerRequestStatus.ACCEPTED);
        assertThat(accepted.profileCalendarApproved()).isFalse();
        if (!enableBeforeDecision) {
            assertThat(musicianCalendar.getSettings(f.actor()).visible()).isFalse();
            assertThat(bandCalendar.getSettings(f.band(), f.actor()).visible()).isFalse();
            enableAllMasters(f, member);
        }

        assertThat(musicianCalendar.getCalendar(f.profile(), date, date, 0, 20).events()).isEmpty();
        assertThat(musicianCalendar.getCalendar(member.profile(), date, date, 0, 20).events()).isEmpty();
        assertThat(bandCalendar.getCalendar(f.band(), date, date, 0, 20).events()).isEmpty();
        tx().executeWithoutResult(status -> {
            Event event = em.find(Event.class, accepted.eventId());
            assertThat(event.getPerformerApprovalStatus()).isEqualTo(EventPerformerApprovalStatus.APPROVED);
            assertThat(event.isProfileCalendarApproved()).isFalse();
            assertThat(event.getManualPerformerName()).isNull();
            assertThat(event.getVenue().getId()).isEqualTo(f.venue());
            assertThat(event.getBand() == null ? event.getMusicianProfile().getId() : event.getBand().getId())
                    .isEqualTo(band ? f.band() : f.profile());
            assertThat(musicianEvents.findCalendarDetails(List.of(event.getId()), f.profile(), List.of(f.band()))).isEmpty();
            assertThat(musicianEvents.findCalendarDetails(List.of(event.getId()), member.profile(), List.of(f.band()))).isEmpty();
            assertThat(bandEvents.findCalendarDetails(List.of(event.getId()), f.band())).isEmpty();
            var detail = eventService.getEventById(event.getId());
            assertThat(detail.venueId()).isEqualTo(f.venue());
            assertThat(detail.bandId()).isEqualTo(band ? f.band() : null);
            assertThat(detail.musicianProfileId()).isEqualTo(band ? null : f.profile());
            assertThat(events.findByVenueOrderByEventDateAscStartTimeAsc(event.getVenue()))
                    .extracting(Event::getId).contains(event.getId());
        });
        verify(notifications).enqueueAll(any());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void explicitEventOptInPublishesOnlyItsTargetWithoutAnyMasterPreference(boolean band) {
        Fixture f = fixture(false);
        MemberFixture member = activeMember(f.band());
        var accepted = invitations.accept(f.actor(), band ? f.bandRequest() : f.personalRequest(), true);
        assertThat(accepted.profileCalendarApproved()).isTrue();
        if (band) {
            assertThat(musicianCalendar.getCalendar(f.profile(), date, date, 0, 20).events()).isEmpty();
            assertThat(bandCalendar.getCalendar(f.band(), date, date, 0, 20).events()).extracting(item -> item.id()).containsExactly(accepted.eventId());
        } else {
            assertThat(musicianCalendar.getCalendar(f.profile(), date, date, 0, 20).events()).extracting(item -> item.id()).containsExactly(accepted.eventId());
            assertThat(bandCalendar.getCalendar(f.band(), date, date, 0, 20).events()).isEmpty();
        }
        musicianCalendar.updateSettings(f.actor(), new MusicianCalendarSettingsUpdate(true, 0L));
        musicianCalendar.updateSettings(member.user(), new MusicianCalendarSettingsUpdate(true, 0L));
        if (band) {
            // Personal permission never overrides the group's closed master.
            assertThat(musicianCalendar.getCalendar(f.profile(), date, date, 0, 20).events()).isEmpty();
            assertThat(musicianCalendar.getCalendar(member.profile(), date, date, 0, 20).events()).isEmpty();
        }
        bandCalendar.updateSettings(f.band(), f.actor(), new MusicianCalendarSettingsUpdate(true, 0L));
        if (band) {
            assertThat(musicianCalendar.getCalendar(f.profile(), date, date, 0, 20).events()).isEmpty();
            assertThat(bandCalendar.getCalendar(f.band(), date, date, 0, 20).events())
                    .extracting(item -> item.id()).containsExactly(accepted.eventId());
            assertThat(musicianCalendar.getCalendar(member.profile(), date, date, 0, 20).events()).isEmpty();
        } else {
            assertThat(musicianCalendar.getCalendar(f.profile(), date, date, 0, 20).events()).extracting(item -> item.id()).containsExactly(accepted.eventId());
            assertThat(bandCalendar.getCalendar(f.band(), date, date, 0, 20).events()).isEmpty();
            assertThat(musicianCalendar.getCalendar(member.profile(), date, date, 0, 20).events()).isEmpty();
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void concurrentAcceptancesWithTheSameFalseOptInAreIdempotent(boolean band) throws Exception {
        Fixture f = fixture(false);
        UUID request = band ? f.bandRequest() : f.personalRequest();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<AcceptAttempt> action = () -> { start.await(5, TimeUnit.SECONDS); return acceptSelection(f.actor(), request, false); };
            var first = executor.submit(action); var second = executor.submit(action); start.countDown();
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .containsOnly(new AcceptAttempt(true, false));
        }
        assertThat(requests.findById(request).orElseThrow().getStatus()).isEqualTo(EventPerformerRequestStatus.ACCEPTED);
        verify(notifications).enqueueAll(any());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void concurrentFalseAndTrueOptInsCommitOneChoiceAndRejectTheConflictingReplay(boolean band) throws Exception {
        Fixture f = fixture(false);
        UUID request = band ? f.bandRequest() : f.personalRequest();
        CountDownLatch start = new CountDownLatch(1);
        List<AcceptAttempt> attempts;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { start.await(5, TimeUnit.SECONDS); return acceptSelection(f.actor(), request, false); });
            var second = executor.submit(() -> { start.await(5, TimeUnit.SECONDS); return acceptSelection(f.actor(), request, true); });
            start.countDown();
            attempts = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
        }
        assertThat(attempts.stream().filter(AcceptAttempt::accepted).toList()).hasSize(1);
        assertThat(attempts.stream().filter(attempt -> !attempt.accepted()).toList()).hasSize(1);
        boolean persistedChoice = attempts.stream().filter(AcceptAttempt::accepted).findFirst().orElseThrow().showOnProfile();
        tx().executeWithoutResult(status -> {
            EventPerformerRequest saved = em.find(EventPerformerRequest.class, request);
            assertThat(saved.getStatus()).isEqualTo(EventPerformerRequestStatus.ACCEPTED);
            assertThat(saved.getVersion()).isEqualTo(1);
            assertThat(saved.getEvent().isProfileCalendarApproved()).isEqualTo(persistedChoice);
            assertThat(saved.getEvent().getPerformerApprovalStatus()).isEqualTo(EventPerformerApprovalStatus.APPROVED);
        });
        verify(notifications).enqueueAll(any());
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void previouslyAcceptedTruePermissionIsNeverChangedByBodylessRetry(boolean band, boolean profileVisibility) {
        Fixture f = fixture(profileVisibility);
        UUID request = band ? f.bandRequest() : f.personalRequest();
        invitations.accept(f.actor(), request, true);
        reset(notifications);
        if (profileVisibility) {
            assertThat(invitations.accept(f.actor(), request).profileCalendarApproved()).isTrue();
        } else {
            assertThatThrownBy(() -> invitations.accept(f.actor(), request))
                    .isInstanceOfSatisfying(SoundConnectException.class, error ->
                            assertThat(error.getErrorType()).isEqualTo(ErrorType.EVENT_PERFORMER_REQUEST_FINALIZED));
        }
        tx().executeWithoutResult(status -> {
            EventPerformerRequest saved = em.find(EventPerformerRequest.class, request);
            assertThat(saved.getEvent().isProfileCalendarApproved()).isTrue();
            assertThat(saved.getStatus()).isEqualTo(EventPerformerRequestStatus.ACCEPTED);
            assertThat(saved.getVersion()).isEqualTo(1);
        });
        verifyNoInteractions(notifications);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void decliningVisibilityOnlyRequestCannotMasqueradeAsAcceptance(boolean band) {
        Fixture f = fixture(true);
        UUID request = band ? f.bandRequest() : f.personalRequest();
        assertThatThrownBy(() -> invitations.accept(f.actor(), request, false))
                .isInstanceOfSatisfying(SoundConnectException.class, error ->
                        assertThat(error.getErrorType()).isEqualTo(ErrorType.INVALID_PARAMETER));
        tx().executeWithoutResult(status -> {
            EventPerformerRequest saved = em.find(EventPerformerRequest.class, request);
            assertThat(saved.getStatus()).isEqualTo(EventPerformerRequestStatus.PENDING);
            assertThat(saved.getEvent().isProfileCalendarApproved()).isFalse();
            assertThat(saved.getEvent().getPerformerApprovalStatus()).isEqualTo(EventPerformerApprovalStatus.APPROVED);
        });
        verifyNoInteractions(notifications);
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void outboxFailureRollsBackThePublicLinkProfileChoiceAndRequestDecisionTogether(boolean band, boolean showOnProfile) {
        Fixture f = fixture(false);
        UUID requestId = band ? f.bandRequest() : f.personalRequest();
        doThrow(new IllegalStateException("Simulated outbox persistence failure"))
                .when(notifications).enqueueAll(any());
        assertThatThrownBy(() -> invitations.accept(f.actor(), requestId, showOnProfile))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("outbox persistence");
        tx().executeWithoutResult(status -> {
            EventPerformerRequest request = em.find(EventPerformerRequest.class, requestId);
            assertThat(request.getStatus()).isEqualTo(EventPerformerRequestStatus.PENDING);
            assertThat(request.getVersion()).isZero();
            assertThat(request.getDecidedAt()).isNull();
            assertThat(request.getDecidedByUserId()).isNull();
            Event event = request.getEvent();
            assertThat(event.getPerformerApprovalStatus()).isEqualTo(EventPerformerApprovalStatus.PENDING);
            assertThat(event.isProfileCalendarApproved()).isFalse();
            assertThat(event.getMusicianProfile()).isNull();
            assertThat(event.getBand()).isNull();
            assertThat(event.getManualPerformerName()).isEqualTo("Performer");
            assertThat(event.getVenue().getId()).isEqualTo(f.venue());
        });
        assertThat(musicianCalendar.getSettings(f.actor()).visible()).isFalse();
        assertThat(bandCalendar.getSettings(f.band(), f.actor()).visible()).isFalse();
    }

    @Test void existingMusicianOriginRowsArePreservedButExcludedFromEveryEventReadAndPublicCalendar() {
        Fixture f = fixture(false);
        List<UUID> legacyIds = tx().execute(status -> {
            MusicianProfile musician = em.find(MusicianProfile.class, f.profile());
            Venue venue = em.find(Venue.class, f.venue());
            Event independent = legacy(musician, null), hosted = legacy(musician, venue);
            return List.of(independent.getId(), hosted.getId());
        });
        for (UUID id : legacyIds) {
            assertThat(events.findById(id)).isPresent();
            assertThat(events.existsByIdAndEventOrigin(id, EventOrigin.VENUE)).isFalse();
            assertThatThrownBy(() -> eventService.getEventById(id)).isInstanceOfSatisfying(SoundConnectException.class,
                    error -> assertThat(error.getErrorType()).isEqualTo(ErrorType.EVENT_NOT_FOUND));
        }
        tx().executeWithoutResult(status -> {
            Venue venue = em.find(Venue.class, f.venue());
            for (List<Event> read : List.of(events.findByEventDate(date), events.findByVenue(venue),
                    events.findByVenueOrderByEventDateAscStartTimeAsc(venue),
                    events.findByVenueAndEventDateBetweenOrderByEventDateAscStartTimeAsc(venue, date, date),
                    events.findByVenue_City_Id(venue.getCity().getId()), events.findByVenue_District_Id(venue.getDistrict().getId()),
                    events.findByVenue_Neighborhood_Id(venue.getNeighborhood().getId()))) {
                assertThat(read).extracting(Event::getId).doesNotContainAnyElementsOf(legacyIds);
                assertThat(read).isNotEmpty();
            }
            assertThat(musicianEvents.findCalendarDetails(legacyIds, f.profile(), List.of())).isEmpty();
        });
        musicianCalendar.updateSettings(f.actor(), new MusicianCalendarSettingsUpdate(true, 0L));
        assertThat(musicianCalendar.getCalendar(f.profile(), date, date, 0, 20).events()).isEmpty();
        assertThat(events.findAllById(legacyIds)).hasSize(2);
    }

    @Test void venueCreationPersistsRequiredOrganizerAndOriginWithAppliedSchema() {
        Fixture f = fixture(false);
        UUID owner = tx().execute(status -> em.find(Venue.class, f.venue()).getOwner().getId());
        when(venueFinder.getVenue(f.venue())).thenAnswer(call -> em.find(Venue.class, f.venue()));
        when(userFinder.getUser(owner)).thenAnswer(call -> em.find(User.class, owner));
        var created = eventService.createEvent(owner,
                new com.berkayb.soundconnect.modules.event.dto.request.EventCreateRequestDto(
                        "Venue-owned creation", null, date, LocalTime.of(21, 0), LocalTime.of(23, 0), null,
                        f.venue(), null, null, "Manual performer"));
        Event persisted = events.findById(created.id()).orElseThrow();
        assertThat(events.existsByIdAndEventOrigin(created.id(), EventOrigin.VENUE)).isTrue();
        assertThat(persisted.getEventOrigin()).isEqualTo(EventOrigin.VENUE);
        assertThat(persisted.getOrganizerUserId()).isEqualTo(owner);
        assertThat(persisted.getVenueApprovalStatus()).isEqualTo(EventVenueApprovalStatus.APPROVED);
        assertThat(persisted.isVenueCalendarApproved()).isTrue();
        assertThat(persisted.isProfileCalendarApproved()).isFalse();
        verifyNoInteractions(notifications);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void competingAcceptAndRejectWithMasterOffCommitExactlyOneDecision(boolean band) throws Exception {
        Fixture f = fixture(false); UUID id = band ? f.bandRequest() : f.personalRequest();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> accept = () -> { start.await(5, TimeUnit.SECONDS); return decide(f.actor(), id, true); };
            Callable<Boolean> reject = () -> { start.await(5, TimeUnit.SECONDS); return decide(f.actor(), id, false); };
            var a = executor.submit(accept); var b = executor.submit(reject); start.countDown();
            assertThat(List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
        }
        assertThat(requests.findById(id).orElseThrow().getStatus()).isIn(EventPerformerRequestStatus.ACCEPTED, EventPerformerRequestStatus.REJECTED);
        verify(notifications).enqueueAll(any());
        assertThat(musicianCalendar.getSettings(f.actor()).visible()).isFalse();
        assertThat(bandCalendar.getSettings(f.band(), f.actor()).visible()).isFalse();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void simultaneousAcceptsRemainIdempotentWithMasterOff(boolean band) throws Exception {
        Fixture f = fixture(true); UUID id = band ? f.bandRequest() : f.personalRequest();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<EventPerformerRequestStatus> action = () -> {
                start.await(5, TimeUnit.SECONDS); return invitations.accept(f.actor(), id).status();
            };
            var a = executor.submit(action); var b = executor.submit(action); start.countDown();
            assertThat(a.get(15, TimeUnit.SECONDS)).isEqualTo(EventPerformerRequestStatus.ACCEPTED);
            assertThat(b.get(15, TimeUnit.SECONDS)).isEqualTo(EventPerformerRequestStatus.ACCEPTED);
        }
        verify(notifications).enqueueAll(any());
    }

    private boolean decide(UUID actor, UUID request, boolean accepted) {
        try {
            if (accepted) invitations.accept(actor, request); else invitations.reject(actor, request);
            return true;
        } catch (SoundConnectException conflict) {
            if (conflict.getErrorType() != ErrorType.EVENT_PERFORMER_REQUEST_FINALIZED) throw conflict;
            return false;
        }
    }

    private AcceptAttempt acceptSelection(UUID actor, UUID request, boolean showOnProfile) {
        try {
            var accepted = invitations.accept(actor, request, showOnProfile);
            assertThat(accepted.status()).isEqualTo(EventPerformerRequestStatus.ACCEPTED);
            return new AcceptAttempt(true, accepted.profileCalendarApproved());
        } catch (SoundConnectException error) {
            if (error.getErrorType() != ErrorType.EVENT_PERFORMER_REQUEST_FINALIZED) throw error;
            return new AcceptAttempt(false, showOnProfile);
        }
    }

    private void enableAllMasters(Fixture f, MemberFixture member) {
        musicianCalendar.updateSettings(f.actor(), new MusicianCalendarSettingsUpdate(true, 0L));
        musicianCalendar.updateSettings(member.user(), new MusicianCalendarSettingsUpdate(true, 0L));
        bandCalendar.updateSettings(f.band(), f.actor(), new MusicianCalendarSettingsUpdate(true, 0L));
    }

    private MemberFixture activeMember(UUID bandId) {
        return tx().execute(status -> {
            User member = user();
            MusicianProfile profile = MusicianProfile.builder().user(member).stageName("Active member").build();
            em.persist(profile);
            em.persist(BandMember.builder().band(em.find(Band.class, bandId)).user(member)
                    .bandRole(BandRole.MEMBER).status(BandMemberShipStatus.ACTIVE).build());
            return new MemberFixture(member.getId(), profile.getId());
        });
    }

    private Event legacy(MusicianProfile musician, Venue venue) {
        Event event = Event.builder().eventOrigin(EventOrigin.MUSICIAN).organizerUserId(musician.getUser().getId())
                .musicianProfile(musician).venue(venue).title("Retained legacy event").eventDate(date).startTime(LocalTime.of(20,0))
                .performerApprovalStatus(EventPerformerApprovalStatus.APPROVED).profileCalendarApproved(true)
                .venueApprovalStatus(venue == null ? EventVenueApprovalStatus.NOT_REQUIRED : EventVenueApprovalStatus.APPROVED)
                .venueCalendarApproved(venue != null).build(); em.persist(event); return event;
    }
    private TransactionTemplate tx() { return new TransactionTemplate(transactionManager); }
    private Fixture fixture(boolean connected) {
        return tx().execute(status -> {
            User actor = user(), owner = user();
            MusicianProfile profile = MusicianProfile.builder().user(actor).stageName("Musician").build(); em.persist(profile);
            City city = City.builder().name("City " + UUID.randomUUID()).build(); em.persist(city);
            District district = District.builder().name("District").city(city).build(); em.persist(district);
            Neighborhood neighborhood = Neighborhood.builder().name("Neighborhood").district(district).build(); em.persist(neighborhood);
            Venue venue = Venue.builder().name("Venue").owner(owner).address("Ankara").city(city).district(district)
                    .neighborhood(neighborhood).status(VenueStatus.APPROVED).build(); em.persist(venue);
            Band band = Band.builder().name("Band " + UUID.randomUUID()).build(); em.persist(band);
            em.persist(BandMember.builder().band(band).user(actor).bandRole(BandRole.FOUNDER).status(BandMemberShipStatus.ACTIVE).build());
            return new Fixture(actor.getId(), profile.getId(), band.getId(), venue.getId(),
                    request(venue, profile, null, connected).getId(), request(venue, null, band, connected).getId());
        });
    }
    private EventPerformerRequest request(Venue venue, MusicianProfile musician, Band band, boolean connected) {
        Event event = Event.builder().venue(venue).title("Venue invitation").eventDate(date).startTime(LocalTime.of(20,0))
                .musicianProfile(connected ? musician : null).band(connected ? band : null).manualPerformerName(connected ? null : "Performer")
                .performerApprovalStatus(connected ? EventPerformerApprovalStatus.APPROVED : EventPerformerApprovalStatus.PENDING).build(); em.persist(event);
        EventPerformerRequest request = EventPerformerRequest.builder().event(event).musicianProfile(musician).band(band)
                .requestedByUserId(venue.getOwner().getId()).status(EventPerformerRequestStatus.PENDING)
                .requestPurpose(connected ? EventPerformerRequestPurpose.PROFILE_VISIBILITY : EventPerformerRequestPurpose.PERFORMER_CONSENT)
                .performerNameSnapshot("Performer").build(); em.persist(request); return request;
    }
    private User user() {
        String name = "rollback_" + UUID.randomUUID().toString().replace("-", "").substring(0,15);
        User user = User.builder().username(name).email(name + "@example.com").password("unused")
                .emailVerified(true).status(UserStatus.ACTIVE).build(); em.persist(user); return user;
    }
    private record Fixture(UUID actor, UUID profile, UUID band, UUID venue, UUID personalRequest, UUID bandRequest) {}
    private record MemberFixture(UUID user, UUID profile) {}
    private record AcceptAttempt(boolean accepted, boolean showOnProfile) {}
}
