package com.berkayb.soundconnect.modules.collab.service;

import com.berkayb.soundconnect.modules.collab.dto.request.*;
import com.berkayb.soundconnect.modules.collab.dto.response.*;
import com.berkayb.soundconnect.modules.collab.entity.*;
import com.berkayb.soundconnect.modules.collab.enums.*;
import com.berkayb.soundconnect.modules.collab.mapper.CollabMapper;
import com.berkayb.soundconnect.modules.collab.repository.*;
import com.berkayb.soundconnect.modules.collab.support.*;
import com.berkayb.soundconnect.modules.instrument.support.InstrumentEntityFinder;
import com.berkayb.soundconnect.modules.location.support.LocationEntityFinder;
import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.profile.shared.media.enums.ProfileType;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.modules.user.support.UserEntityFinder;
import com.berkayb.soundconnect.shared.exception.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.*;

import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CollabServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-11T09:00:00Z");

    @Mock CollabRepository listingRepository;
    @Mock CollabActorRepository actorRepository;
    @Mock CollabApplicationRepository applicationRepository;
    @Mock CollabJobRepository jobRepository;
    @Mock CollabReviewRepository reviewRepository;
    @Mock CollabSavedListingRepository savedRepository;
    @Mock CollabReportRepository reportRepository;
    @Mock CollabActorService actorService;
    @Mock CollabMapper mapper;
    @Mock UserEntityFinder userFinder;
    @Mock UserRepository userRepository;
    @Mock LocationEntityFinder locationFinder;
    @Mock InstrumentEntityFinder instrumentFinder;
    @Mock CollabTimeProvider timeProvider;
    @Mock ApplicationEventPublisher eventPublisher;

    @InjectMocks CollabService service;

    @BeforeEach
    void defaults() {
        lenient().when(timeProvider.now()).thenReturn(NOW);
        lenient().when(applicationRepository.findAppliedListingIds(any(), anyCollection())).thenReturn(Set.of());
        lenient().when(savedRepository.findSavedListingIds(any(), anyCollection())).thenReturn(Set.of());
        lenient().when(applicationRepository.countByListingIds(anyCollection())).thenReturn(List.of());
        lenient().when(reviewRepository.findReviewedJobIds(any(), anyCollection())).thenReturn(Set.of());
    }

    @Test
    void draftDetailIsHiddenFromNonOwner() {
        User owner = user("00000000-0000-0000-0000-000000000001");
        UUID viewerId = uuid("00000000-0000-0000-0000-000000000002");
        Collab listing = listing(owner, CollabListingStatus.DRAFT);
        when(listingRepository.findByIdForUpdate(listing.getId())).thenReturn(Optional.of(listing));

        assertThatThrownBy(() -> service.detail(viewerId, listing.getId()))
                .isInstanceOfSatisfying(SoundConnectException.class,
                        error -> assertThat(error.getErrorType()).isEqualTo(ErrorType.COLLAB_NOT_FOUND));
        verifyNoInteractions(mapper);
    }

    @Test
    void closedDetailIsVisibleToAnApplicantButNotToAStranger() {
        User owner = user("00000000-0000-0000-0000-000000000011");
        UUID applicantId = uuid("00000000-0000-0000-0000-000000000012");
        UUID strangerId = uuid("00000000-0000-0000-0000-000000000013");
        Collab listing = listing(owner, CollabListingStatus.CLOSED);
        listing.setClosureReason(CollabClosureReason.OWNER_CLOSED);
        CollabListingResponse response = mock(CollabListingResponse.class);
        when(listingRepository.findByIdForUpdate(listing.getId())).thenReturn(Optional.of(listing));
        when(applicationRepository.existsByListingIdAndApplicantUserId(listing.getId(), applicantId)).thenReturn(true);
        when(applicationRepository.existsByListingIdAndApplicantUserId(listing.getId(), strangerId)).thenReturn(false);
        when(mapper.listing(eq(listing), any())).thenReturn(response);

        assertThat(service.detail(applicantId, listing.getId())).isSameAs(response);
        assertThatThrownBy(() -> service.detail(strangerId, listing.getId()))
                .isInstanceOfSatisfying(SoundConnectException.class,
                        error -> assertThat(error.getErrorType()).isEqualTo(ErrorType.COLLAB_NOT_FOUND));
    }

    @Test
    void publiclyOpenDueDetailMaterializesExpiryAndStillReturnsThatTransition() {
        User owner = user("00000000-0000-0000-0000-000000000014");
        UUID viewerId = uuid("00000000-0000-0000-0000-000000000015");
        Collab listing = listing(owner, CollabListingStatus.OPEN);
        listing.setCadence(CollabCadence.EXTRA);
        listing.setScheduledAt(NOW.minusSeconds(1));
        listing.setExpiresAt(NOW.minusSeconds(1));
        CollabListingResponse response = mock(CollabListingResponse.class);
        when(listingRepository.findByIdForUpdate(listing.getId())).thenReturn(Optional.of(listing));
        when(applicationRepository.findByListingIdAndStatus(listing.getId(), CollabApplicationStatus.PENDING))
                .thenReturn(List.of());
        when(mapper.listing(eq(listing), any())).thenReturn(response);

        assertThat(service.detail(viewerId, listing.getId())).isSameAs(response);
        assertThat(listing.getStatus()).isEqualTo(CollabListingStatus.EXPIRED);
        verify(applicationRepository).invalidatePending(listing.getId(), null,
                CollabApplicationStatus.PENDING,
                CollabApplicationStatus.INVALIDATED_BY_LISTING_CLOSURE, NOW);
    }

    @Test
    void applicationActorMustMatchWantedProfileType() {
        UUID applicantId = uuid("00000000-0000-0000-0000-000000000021");
        User applicant = user(applicantId.toString());
        User owner = user("00000000-0000-0000-0000-000000000022");
        CollabActor musician = actor("00000000-0000-0000-0000-000000000023", ProfileType.MUSICIAN);
        Collab listing = listing(owner, CollabListingStatus.OPEN);
        listing.setWantedType(CollabWantedType.VENUE);
        when(userRepository.findByIdForUpdate(applicantId)).thenReturn(Optional.of(applicant));
        when(applicationRepository.findByApplicantUserIdAndClientRequestId(eq(applicantId), any())).thenReturn(Optional.empty());
        when(actorService.requireOwned(applicantId, musician.getId())).thenReturn(musician);
        when(listingRepository.findByIdForUpdate(listing.getId())).thenReturn(Optional.of(listing));

        CollabApplicationCreateRequest request = new CollabApplicationCreateRequest(
                UUID.randomUUID(), musician.getId(), "+90 532 111 22 33", "Musaitim");
        assertThatThrownBy(() -> service.apply(applicantId, listing.getId(), request))
                .isInstanceOfSatisfying(SoundConnectException.class,
                        error -> assertThat(error.getErrorType()).isEqualTo(ErrorType.COLLAB_INVALID_ACTOR));
        verify(applicationRepository, never()).saveAndFlush(any());
    }

    @Test
    void exactApplicationReplayDoesNotRequireProfileOwnershipAgain() {
        UUID applicantId = uuid("00000000-0000-0000-0000-000000000031");
        User applicant = user(applicantId.toString());
        User owner = user("00000000-0000-0000-0000-000000000032");
        UUID actorId = uuid("00000000-0000-0000-0000-000000000033");
        UUID requestId = uuid("00000000-0000-0000-0000-000000000034");
        Collab listing = listing(owner, CollabListingStatus.CLOSED);
        CollabApplication existing = CollabApplication.builder()
                .listing(listing).applicantUser(applicant).applicantActor(actor(actorId.toString(), ProfileType.MUSICIAN))
                .clientRequestId(requestId).phoneSnapshot("+905321112233").message("Musaitim")
                .requestPayloadHash(CollabPayloadHasher.hash(listing.getId(), actorId, "+905321112233", "Musaitim"))
                .status(CollabApplicationStatus.ACCEPTED).submittedAt(NOW).statusChangedAt(NOW).build();
        existing.setId(uuid("00000000-0000-0000-0000-000000000035"));
        CollabApplicationResponse response = mock(CollabApplicationResponse.class);
        when(userRepository.findByIdForUpdate(applicantId)).thenReturn(Optional.of(applicant));
        when(applicationRepository.findByApplicantUserIdAndClientRequestId(applicantId, requestId))
                .thenReturn(Optional.of(existing));
        when(mapper.application(eq(existing), eq(applicantId), any())).thenReturn(response);

        assertThat(service.apply(applicantId, listing.getId(), new CollabApplicationCreateRequest(
                requestId, actorId, "+90 532 111 22 33", "Musaitim"))).isSameAs(response);
        verify(actorService, never()).requireOwned(any(), any());
        verify(listingRepository, never()).findByIdForUpdate(any());
    }

    @Test
    void applicationReplayWithDifferentPayloadIsAConflict() {
        UUID applicantId = uuid("00000000-0000-0000-0000-000000000036");
        User applicant = user(applicantId.toString());
        UUID actorId = uuid("00000000-0000-0000-0000-000000000037");
        UUID requestId = uuid("00000000-0000-0000-0000-000000000038");
        CollabApplication existing = CollabApplication.builder()
                .requestPayloadHash(CollabPayloadHasher.hash(UUID.randomUUID(), actorId, "+905321112233", "Eski"))
                .build();
        when(userRepository.findByIdForUpdate(applicantId)).thenReturn(Optional.of(applicant));
        when(applicationRepository.findByApplicantUserIdAndClientRequestId(applicantId, requestId))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.apply(applicantId, UUID.randomUUID(),
                new CollabApplicationCreateRequest(requestId, actorId, "+90 532 111 22 33", "Yeni")))
                .isInstanceOfSatisfying(SoundConnectException.class,
                        error -> assertThat(error.getErrorType()).isEqualTo(ErrorType.COLLAB_IDEMPOTENCY_CONFLICT));
    }

    @Test
    void regularFeeIsRejectedForNonVenuePublisher() {
        UUID ownerId = uuid("00000000-0000-0000-0000-000000000071");
        User owner = user(ownerId.toString());
        CollabActor musician = actor("00000000-0000-0000-0000-000000000072", ProfileType.MUSICIAN);
        City city = city("00000000-0000-0000-0000-000000000073");
        when(userRepository.findByIdForUpdate(ownerId)).thenReturn(Optional.of(owner));
        when(actorService.requireOwned(ownerId, musician.getId())).thenReturn(musician);
        when(locationFinder.getCity(city.getId())).thenReturn(city);

        CollabDraftCreateRequest request = draft(musician.getId(), city.getId(), CollabCadence.REGULAR,
                CollabWantedType.BAND, null, null, null, 10_000L, null);
        assertThatThrownBy(() -> service.createDraft(ownerId, request))
                .isInstanceOfSatisfying(SoundConnectException.class,
                        error -> assertThat(error.getErrorType()).isEqualTo(ErrorType.COLLAB_FEE_INVALID));
    }

    @Test
    void extraScheduleBeyondSevenDaysIsRejected() {
        UUID ownerId = uuid("00000000-0000-0000-0000-000000000074");
        User owner = user(ownerId.toString());
        CollabActor studio = actor("00000000-0000-0000-0000-000000000075", ProfileType.STUDIO);
        City city = city("00000000-0000-0000-0000-000000000076");
        when(userRepository.findByIdForUpdate(ownerId)).thenReturn(Optional.of(owner));
        when(actorService.requireOwned(ownerId, studio.getId())).thenReturn(studio);
        when(locationFinder.getCity(city.getId())).thenReturn(city);

        CollabDraftCreateRequest request = new CollabDraftCreateRequest(UUID.randomUUID(), studio.getId(),
                CollabCadence.EXTRA, CollabWantedType.BAND, null, null, null,
                "Grup ariyoruz", "Ekstra sahnemiz icin bir grup arkadasi ariyoruz.", city.getId(), List.of(),
                NOW.plus(Duration.ofDays(7)).plusSeconds(1), null, null);
        assertThatThrownBy(() -> service.createDraft(ownerId, request))
                .isInstanceOfSatisfying(SoundConnectException.class,
                        error -> assertThat(error.getErrorType()).isEqualTo(ErrorType.COLLAB_CADENCE_FIELDS_INVALID));
    }

    @Test
    void musicianWantedTypeRequiresExactlyOneStructuredSpecialty() {
        UUID ownerId = uuid("00000000-0000-0000-0000-000000000077");
        User owner = user(ownerId.toString());
        CollabActor venue = actor("00000000-0000-0000-0000-000000000078", ProfileType.VENUE);
        when(userRepository.findByIdForUpdate(ownerId)).thenReturn(Optional.of(owner));
        when(actorService.requireOwned(ownerId, venue.getId())).thenReturn(venue);

        CollabDraftCreateRequest request = draft(venue.getId(), UUID.randomUUID(), CollabCadence.REGULAR,
                CollabWantedType.MUSICIAN, null, null, null, null, null);
        assertThatThrownBy(() -> service.createDraft(ownerId, request))
                .isInstanceOfSatisfying(SoundConnectException.class,
                        error -> assertThat(error.getErrorType()).isEqualTo(ErrorType.COLLAB_SPECIALTY_INVALID));
    }

    @Test
    void normalizedListingTextMustStillMeetDomainLengthsAfterTrimming() {
        UUID ownerId = uuid("00000000-0000-0000-0000-000000000079");
        User owner = user(ownerId.toString());
        CollabActor venue = actor("00000000-0000-0000-0000-000000000080", ProfileType.VENUE);
        when(userRepository.findByIdForUpdate(ownerId)).thenReturn(Optional.of(owner));
        when(actorService.requireOwned(ownerId, venue.getId())).thenReturn(venue);
        CollabDraftCreateRequest request = new CollabDraftCreateRequest(
                UUID.randomUUID(), venue.getId(), CollabCadence.REGULAR, CollabWantedType.BAND,
                null, null, null, "a    ",
                "Düzenli sahne programımız için akustik bir grup arıyoruz.", UUID.randomUUID(),
                List.of(), null, null, null);

        assertThatThrownBy(() -> service.createDraft(ownerId, request))
                .isInstanceOfSatisfying(SoundConnectException.class,
                        error -> assertThat(error.getErrorType()).isEqualTo(ErrorType.VALIDATION_ERROR));
        verifyNoInteractions(locationFinder);
    }

    @Test
    void acceptingApplicationClosesListingInvalidatesOthersAndCreatesOneJob() {
        User owner = user("00000000-0000-0000-0000-000000000041");
        User applicant = user("00000000-0000-0000-0000-000000000042");
        User otherApplicant = user("00000000-0000-0000-0000-000000000043");
        Collab listing = listing(owner, CollabListingStatus.OPEN);
        CollabActor applicantActor = actor("00000000-0000-0000-0000-000000000044", ProfileType.MUSICIAN);
        CollabApplication selected = application(listing, applicant, applicantActor,
                "00000000-0000-0000-0000-000000000045");
        CollabApplication other = application(listing, otherApplicant,
                actor("00000000-0000-0000-0000-000000000046", ProfileType.MUSICIAN),
                "00000000-0000-0000-0000-000000000047");
        CollabJobResponse response = mock(CollabJobResponse.class);
        when(applicationRepository.findListingId(selected.getId())).thenReturn(Optional.of(listing.getId()));
        when(listingRepository.findByIdForUpdate(listing.getId())).thenReturn(Optional.of(listing));
        when(applicationRepository.findByIdForUpdate(selected.getId())).thenReturn(Optional.of(selected));
        when(applicationRepository.findByListingIdAndStatus(listing.getId(), CollabApplicationStatus.PENDING))
                .thenReturn(List.of(selected, other));
        when(jobRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            CollabJob job = invocation.getArgument(0);
            job.setId(uuid("00000000-0000-0000-0000-000000000048"));
            return job;
        });
        when(mapper.job(any(), eq(owner.getId()), eq(false), any())).thenReturn(response);

        assertThat(service.accept(owner.getId(), selected.getId(), new ExpectedVersionRequest(0L))).isSameAs(response);
        assertThat(selected.getStatus()).isEqualTo(CollabApplicationStatus.ACCEPTED);
        assertThat(listing.getStatus()).isEqualTo(CollabListingStatus.CLOSED);
        assertThat(listing.getClosureReason()).isEqualTo(CollabClosureReason.MATCHED);
        verify(applicationRepository).invalidatePending(listing.getId(), selected.getId(),
                CollabApplicationStatus.PENDING,
                CollabApplicationStatus.INVALIDATED_BY_LISTING_CLOSURE, NOW);
        verify(savedRepository).deleteByListingId(listing.getId());
        verify(jobRepository, times(1)).saveAndFlush(any(CollabJob.class));
    }

    @Test
    void secondCompletionConfirmationCompletesJobAndIncrementsBothActorsOnce() {
        User publisher = user("00000000-0000-0000-0000-000000000051");
        User applicant = user("00000000-0000-0000-0000-000000000052");
        Collab listing = listing(publisher, CollabListingStatus.CLOSED);
        CollabActor publisherActor = actor("00000000-0000-0000-0000-000000000053", ProfileType.VENUE);
        CollabActor applicantActor = actor("00000000-0000-0000-0000-000000000054", ProfileType.MUSICIAN);
        CollabJob job = CollabJob.builder().listing(listing).publisherUser(publisher).applicantUser(applicant)
                .publisherActor(publisherActor).applicantActor(applicantActor).status(CollabJobStatus.ACTIVE)
                .applicantConfirmedAt(NOW.minusSeconds(60)).build();
        job.setId(uuid("00000000-0000-0000-0000-000000000055"));
        CollabJobResponse response = mock(CollabJobResponse.class);
        when(jobRepository.findByIdForUpdate(job.getId())).thenReturn(Optional.of(job));
        when(actorRepository.findByIdForUpdate(publisherActor.getId())).thenReturn(Optional.of(publisherActor));
        when(actorRepository.findByIdForUpdate(applicantActor.getId())).thenReturn(Optional.of(applicantActor));
        when(mapper.job(eq(job), eq(publisher.getId()), eq(false), any())).thenReturn(response);

        assertThat(service.confirmCompletion(publisher.getId(), job.getId(), new ExpectedVersionRequest(0L)))
                .isSameAs(response);
        assertThat(job.getStatus()).isEqualTo(CollabJobStatus.COMPLETED);
        assertThat(job.getCompletedAt()).isEqualTo(NOW);
        assertThat(publisherActor.getCompletedJobCount()).isOne();
        assertThat(applicantActor.getCompletedJobCount()).isOne();
        verify(jobRepository).flush();
    }

    @Test
    void completedJobCanBeReviewedOnceAndUpdatesTargetRating() {
        User publisher = user("00000000-0000-0000-0000-000000000081");
        User applicant = user("00000000-0000-0000-0000-000000000082");
        Collab listing = listing(publisher, CollabListingStatus.CLOSED);
        CollabActor publisherActor = actor("00000000-0000-0000-0000-000000000083", ProfileType.VENUE);
        CollabActor applicantActor = actor("00000000-0000-0000-0000-000000000084", ProfileType.MUSICIAN);
        CollabJob job = CollabJob.builder().listing(listing).publisherUser(publisher).applicantUser(applicant)
                .publisherActor(publisherActor).applicantActor(applicantActor).status(CollabJobStatus.COMPLETED)
                .publisherConfirmedAt(NOW.minusSeconds(120)).applicantConfirmedAt(NOW.minusSeconds(60))
                .completedAt(NOW.minusSeconds(60)).build();
        job.setId(uuid("00000000-0000-0000-0000-000000000085"));
        UUID requestId = uuid("00000000-0000-0000-0000-000000000086");
        CollabReviewResponse response = mock(CollabReviewResponse.class);
        when(userRepository.findByIdForUpdate(publisher.getId())).thenReturn(Optional.of(publisher));
        when(reviewRepository.findByReviewerUserIdAndClientRequestId(publisher.getId(), requestId))
                .thenReturn(Optional.empty());
        when(jobRepository.findByIdForUpdate(job.getId())).thenReturn(Optional.of(job));
        when(reviewRepository.findByJobIdAndReviewerActorId(job.getId(), publisherActor.getId()))
                .thenReturn(Optional.empty());
        when(actorRepository.findByIdForUpdate(applicantActor.getId())).thenReturn(Optional.of(applicantActor));
        when(userFinder.getUser(publisher.getId())).thenReturn(publisher);
        when(reviewRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            CollabReview review = invocation.getArgument(0);
            review.setId(uuid("00000000-0000-0000-0000-000000000087"));
            return review;
        });
        when(mapper.review(any())).thenReturn(response);

        assertThat(service.review(publisher.getId(), job.getId(),
                new CollabReviewCreateRequest(requestId, 5, "Harika bir is ortakligiydi."))).isSameAs(response);
        assertThat(applicantActor.getRatingSum()).isEqualTo(5);
        assertThat(applicantActor.getReviewCount()).isOne();
        verify(reviewRepository).saveAndFlush(any(CollabReview.class));
    }

    @Test
    void dueBatchPersistsExpiryAndInvalidatesPendingApplications() {
        User owner = user("00000000-0000-0000-0000-000000000061");
        User applicant = user("00000000-0000-0000-0000-000000000062");
        Collab listing = listing(owner, CollabListingStatus.OPEN);
        listing.setCadence(CollabCadence.EXTRA);
        listing.setScheduledAt(NOW.minusSeconds(1));
        listing.setExpiresAt(NOW.minusSeconds(1));
        CollabApplication pending = application(listing, applicant,
                actor("00000000-0000-0000-0000-000000000063", ProfileType.MUSICIAN),
                "00000000-0000-0000-0000-000000000064");
        when(listingRepository.findDueIds(eq(CollabListingStatus.OPEN), eq(NOW), any(Pageable.class)))
                .thenReturn(List.of(listing.getId()));
        when(listingRepository.findByIdForUpdate(listing.getId())).thenReturn(Optional.of(listing));
        when(applicationRepository.findByListingIdAndStatus(listing.getId(), CollabApplicationStatus.PENDING))
                .thenReturn(List.of(pending));

        assertThat(service.expireDueBatch(100)).isOne();
        assertThat(listing.getStatus()).isEqualTo(CollabListingStatus.EXPIRED);
        assertThat(listing.getClosureReason()).isEqualTo(CollabClosureReason.EXPIRED);
        verify(applicationRepository).invalidatePending(listing.getId(), null,
                CollabApplicationStatus.PENDING,
                CollabApplicationStatus.INVALIDATED_BY_LISTING_CLOSURE, NOW);
        verify(savedRepository).deleteByListingId(listing.getId());
        verify(eventPublisher, times(2)).publishEvent(any(Object.class));
    }

    @Test
    void saveUsesSharedVisibilityLockAndDatabaseIdempotentInsert() {
        UUID viewerId = uuid("00000000-0000-0000-0000-000000000091");
        User owner = user("00000000-0000-0000-0000-000000000092");
        Collab listing = listing(owner, CollabListingStatus.OPEN);
        CollabListingResponse response = mock(CollabListingResponse.class);
        when(userRepository.findByIdForUpdate(viewerId)).thenReturn(Optional.of(user(viewerId.toString())));
        when(listingRepository.findVisibleOpenByIdForShare(listing.getId(), CollabListingStatus.OPEN, NOW))
                .thenReturn(Optional.of(listing));
        when(mapper.listing(eq(listing), any())).thenReturn(response);

        assertThat(service.save(viewerId, listing.getId())).isSameAs(response);

        verify(savedRepository).insertIfAbsent(any(UUID.class), eq(viewerId), eq(listing.getId()));
        verify(listingRepository, never()).findByIdForUpdate(listing.getId());
        verify(savedRepository, never()).save(any());
    }

    @Test
    void savedMineDelegatesVisibilityAndExpiryToTheDatabaseQuery() {
        UUID viewerId = uuid("00000000-0000-0000-0000-000000000093");
        Collab listing = listing(user("00000000-0000-0000-0000-000000000094"), CollabListingStatus.OPEN);
        CollabSavedListing saved = CollabSavedListing.builder().listing(listing).build();
        saved.setId(uuid("00000000-0000-0000-0000-000000000095"));
        CollabListingResponse response = mock(CollabListingResponse.class);
        when(savedRepository.findVisibleByUserId(eq(viewerId), eq(CollabListingStatus.OPEN), eq(NOW), any()))
                .thenAnswer(invocation -> {
                    Pageable pageable = invocation.getArgument(3);
                    return new PageImpl<>(List.of(saved), pageable, 1);
                });
        when(mapper.listing(eq(listing), any())).thenReturn(response);

        assertThat(service.savedMine(viewerId, 0, 20).content()).containsExactly(response);

        verify(savedRepository).findVisibleByUserId(eq(viewerId), eq(CollabListingStatus.OPEN), eq(NOW), any());
    }

    @Test
    void reportRejectsAListingThatTheReporterCannotView() {
        UUID reporterId = uuid("00000000-0000-0000-0000-000000000096");
        User reporter = user(reporterId.toString());
        Collab listing = listing(user("00000000-0000-0000-0000-000000000097"), CollabListingStatus.DRAFT);
        UUID requestId = uuid("00000000-0000-0000-0000-000000000098");
        when(userRepository.findByIdForUpdate(reporterId)).thenReturn(Optional.of(reporter));
        when(reportRepository.findByReporterUserIdAndClientRequestId(reporterId, requestId))
                .thenReturn(Optional.empty());
        when(reportRepository.findByReporterUserIdAndListingId(reporterId, listing.getId()))
                .thenReturn(Optional.empty());
        when(listingRepository.findById(listing.getId())).thenReturn(Optional.of(listing));

        assertThatThrownBy(() -> service.report(reporterId, listing.getId(),
                new CollabReportCreateRequest(requestId, CollabReportReason.SPAM, null)))
                .isInstanceOfSatisfying(SoundConnectException.class,
                        error -> assertThat(error.getErrorType()).isEqualTo(ErrorType.COLLAB_NOT_FOUND));
        verify(reportRepository, never()).saveAndFlush(any());
    }

    @Test
    void exactReportReplaySurvivesAListingBecomingHidden() {
        UUID reporterId = uuid("00000000-0000-0000-0000-000000000099");
        User reporter = user(reporterId.toString());
        Collab listing = listing(user("00000000-0000-0000-0000-000000000100"), CollabListingStatus.CLOSED);
        UUID requestId = uuid("00000000-0000-0000-0000-000000000101");
        CollabReport existing = CollabReport.builder()
                .listing(listing)
                .reporterUser(reporter)
                .clientRequestId(requestId)
                .requestPayloadHash(CollabPayloadHasher.hash(listing.getId(), CollabReportReason.SPAM, null))
                .reason(CollabReportReason.SPAM)
                .reportedAt(NOW.minusSeconds(60))
                .build();
        existing.setId(uuid("00000000-0000-0000-0000-000000000102"));
        when(userRepository.findByIdForUpdate(reporterId)).thenReturn(Optional.of(reporter));
        when(reportRepository.findByReporterUserIdAndClientRequestId(reporterId, requestId))
                .thenReturn(Optional.of(existing));

        CollabReportResponse response = service.report(reporterId, listing.getId(),
                new CollabReportCreateRequest(requestId, CollabReportReason.SPAM, null));

        assertThat(response.id()).isEqualTo(existing.getId());
        assertThat(response.listingId()).isEqualTo(listing.getId());
        verifyNoInteractions(listingRepository);
    }

    private Collab listing(User owner, CollabListingStatus status) {
        CollabActor publisher = actor(UUID.randomUUID().toString(), ProfileType.VENUE);
        Collab value = Collab.builder().owner(owner).publisherActor(publisher).status(status)
                .cadence(CollabCadence.REGULAR).wantedType(CollabWantedType.MUSICIAN)
                .title("Bas gitarist ariyoruz").description("Sahne icin deneyimli bir ekip arkadasi ariyoruz.")
                .genres(new ArrayList<>()).build();
        value.setId(UUID.randomUUID());
        return value;
    }

    private CollabDraftCreateRequest draft(UUID publisherActorId, UUID cityId, CollabCadence cadence,
                                           CollabWantedType wantedType, UUID instrumentId, CollabBranch branch,
                                           String customSpecialty, Long fee, String currency) {
        return new CollabDraftCreateRequest(UUID.randomUUID(), publisherActorId, cadence, wantedType,
                instrumentId, branch, customSpecialty, "Grup ariyoruz",
                "Sahne programimiz icin deneyimli bir ekip arkadasi ariyoruz.", cityId, List.of(),
                cadence == CollabCadence.EXTRA ? NOW.plus(Duration.ofDays(1)) : null, fee, currency);
    }

    private City city(String id) {
        City value = new City();
        value.setId(uuid(id));
        value.setName("Istanbul");
        return value;
    }

    private CollabApplication application(Collab listing, User user, CollabActor actor, String id) {
        CollabApplication value = CollabApplication.builder().listing(listing).applicantUser(user)
                .applicantActor(actor).status(CollabApplicationStatus.PENDING)
                .phoneSnapshot("+905321112233").submittedAt(NOW).statusChangedAt(NOW).build();
        value.setId(uuid(id));
        return value;
    }

    private CollabActor actor(String id, ProfileType type) {
        CollabActor value = CollabActor.builder().profileType(type).sourceProfileId(UUID.randomUUID())
                .displayName(type.name()).build();
        value.setId(uuid(id));
        return value;
    }

    private User user(String id) {
        User value = new User();
        value.setId(uuid(id));
        value.setUsername("user-" + id.substring(id.length() - 4));
        return value;
    }

    private UUID uuid(String value) { return UUID.fromString(value); }
}
