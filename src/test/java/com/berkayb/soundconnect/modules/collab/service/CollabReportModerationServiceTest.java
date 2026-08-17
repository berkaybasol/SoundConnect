package com.berkayb.soundconnect.modules.collab.service;

import com.berkayb.soundconnect.modules.collab.dto.request.CollabReportReviewRequest;
import com.berkayb.soundconnect.modules.collab.dto.response.CollabReportAdminResponse;
import com.berkayb.soundconnect.modules.collab.entity.Collab;
import com.berkayb.soundconnect.modules.collab.entity.CollabActor;
import com.berkayb.soundconnect.modules.collab.entity.CollabApplication;
import com.berkayb.soundconnect.modules.collab.entity.CollabReport;
import com.berkayb.soundconnect.modules.collab.entity.CollabReportListingEvidence;
import com.berkayb.soundconnect.modules.collab.enums.CollabApplicationStatus;
import com.berkayb.soundconnect.modules.collab.enums.CollabBranch;
import com.berkayb.soundconnect.modules.collab.enums.CollabCadence;
import com.berkayb.soundconnect.modules.collab.enums.CollabClosureReason;
import com.berkayb.soundconnect.modules.collab.enums.CollabListingStatus;
import com.berkayb.soundconnect.modules.collab.enums.CollabReportDecision;
import com.berkayb.soundconnect.modules.collab.enums.CollabReportReason;
import com.berkayb.soundconnect.modules.collab.enums.CollabReportStatus;
import com.berkayb.soundconnect.modules.collab.enums.CollabWantedType;
import com.berkayb.soundconnect.modules.collab.event.CollabNotificationEvent;
import com.berkayb.soundconnect.modules.collab.repository.CollabApplicationRepository;
import com.berkayb.soundconnect.modules.collab.repository.CollabReportRepository;
import com.berkayb.soundconnect.modules.collab.repository.CollabRepository;
import com.berkayb.soundconnect.modules.collab.repository.CollabSavedListingRepository;
import com.berkayb.soundconnect.modules.collab.support.CollabTimeProvider;
import com.berkayb.soundconnect.modules.instrument.entity.Instrument;
import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.support.UserEntityFinder;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CollabReportModerationServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-11T12:00:00Z");

    @Mock CollabReportRepository reportRepository;
    @Mock CollabRepository listingRepository;
    @Mock CollabApplicationRepository applicationRepository;
    @Mock CollabSavedListingRepository savedListingRepository;
    @Mock UserEntityFinder userFinder;
    @Mock CollabTimeProvider timeProvider;
    @Mock ApplicationEventPublisher eventPublisher;

    @InjectMocks CollabReportModerationService service;

    @BeforeEach
    void clock() {
        lenient().when(timeProvider.now()).thenReturn(NOW);
    }

    @Test
    void adminListIncludesCompleteReadOnlyListingEvidence() {
        Collab listing = listing(user("00000000-0000-0000-0000-000000000101"));
        CollabReport report = report(listing,
                user("00000000-0000-0000-0000-000000000102"));
        when(reportRepository.findAdminPage(
                eq(CollabReportStatus.OPEN), eq(CollabReportReason.SPAM), any()))
                .thenReturn(new PageImpl<>(List.of(report)));

        var page = service.list(CollabReportStatus.OPEN, CollabReportReason.SPAM, 0, 20);

        assertThat(page.content()).hasSize(1);
        CollabReportAdminResponse response = page.content().getFirst();
        assertListingEvidence(response, report);
        listing.setTitle("Degisen baslik");
        listing.setDescription("Degisen ve snapshot degerlerini etkilememesi gereken ilan aciklamasi.");
        listing.getPublisherActor().setDisplayName("Degisen yayinci");
        listing.getInstrument().setName("Degisen enstruman");
        listing.getCity().setName("Degisen sehir");
        listing.getGenres().add("Metal");
        assertListingEvidence(response, report);
        assertThat(response.listingGenres()).doesNotContain("Metal");
        assertThatThrownBy(() -> response.listingGenres().add("Metal"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void reviewRejectsResolutionNoteThatIsTooShortAfterTrimming() {
        UUID reportId = UUID.randomUUID();

        assertThatThrownBy(() -> service.review(
                UUID.randomUUID(),
                reportId,
                new CollabReportReviewRequest(CollabReportDecision.DISMISS, 0L, "x    ")
        ))
                .isInstanceOf(SoundConnectException.class)
                .extracting("errorType")
                .isEqualTo(ErrorType.VALIDATION_ERROR);

        verify(reportRepository, never()).findByIdForUpdate(reportId);
    }

    @Test
    void removingListingClosesItInvalidatesPendingApplicationsAndNotifiesEveryone() {
        User owner = user("00000000-0000-0000-0000-000000000001");
        User reporter = user("00000000-0000-0000-0000-000000000002");
        User applicant = user("00000000-0000-0000-0000-000000000003");
        User admin = user("00000000-0000-0000-0000-000000000004");
        Collab listing = listing(owner);
        CollabReport report = report(listing, reporter);
        CollabApplication pending = CollabApplication.builder()
                .listing(listing)
                .applicantUser(applicant)
                .status(CollabApplicationStatus.PENDING)
                .build();
        pending.setId(UUID.fromString("00000000-0000-0000-0000-000000000005"));

        when(reportRepository.findListingId(report.getId())).thenReturn(Optional.of(listing.getId()));
        when(listingRepository.findByIdForUpdate(listing.getId())).thenReturn(Optional.of(listing));
        when(reportRepository.findByIdForUpdate(report.getId())).thenReturn(Optional.of(report));
        when(applicationRepository.findByListingIdAndStatus(
                listing.getId(), CollabApplicationStatus.PENDING)).thenReturn(List.of(pending));
        when(userFinder.getUser(admin.getId())).thenReturn(admin);

        var response = service.review(admin.getId(), report.getId(),
                new CollabReportReviewRequest(CollabReportDecision.REMOVE_LISTING, 0L,
                        "Topluluk kurallarını ihlal ediyor."));

        assertThat(listing.getStatus()).isEqualTo(CollabListingStatus.CLOSED);
        assertThat(listing.getClosureReason()).isEqualTo(CollabClosureReason.ADMIN_REMOVED);
        assertThat(listing.getClosedAt()).isEqualTo(NOW);
        assertThat(report.getStatus()).isEqualTo(CollabReportStatus.ACTIONED);
        assertThat(report.getReviewedByUser()).isSameAs(admin);
        assertThat(response.reviewDecision()).isEqualTo(CollabReportDecision.REMOVE_LISTING);
        assertListingEvidence(response, report);
        assertThat(response.listingStatus()).isEqualTo(CollabListingStatus.CLOSED);
        assertThat(response.listingStatusAtReport()).isEqualTo(CollabListingStatus.OPEN);
        verify(applicationRepository).invalidatePending(listing.getId(), null,
                CollabApplicationStatus.PENDING,
                CollabApplicationStatus.INVALIDATED_BY_LISTING_CLOSURE, NOW);
        verify(savedListingRepository).deleteByListingId(listing.getId());

        ArgumentCaptor<CollabNotificationEvent> eventCaptor =
                ArgumentCaptor.forClass(CollabNotificationEvent.class);
        verify(eventPublisher, org.mockito.Mockito.times(3)).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getAllValues())
                .extracting(CollabNotificationEvent::recipientId)
                .containsExactlyInAnyOrder(owner.getId(), applicant.getId(), reporter.getId());
        assertThat(eventCaptor.getAllValues())
                .extracting(CollabNotificationEvent::title)
                .contains("Collab ilanın kaldırıldı", "Başvuru geçersizleşti", "Bildirimin sonuçlandırıldı");
    }

    @ParameterizedTest
    @MethodSource("terminalListingCases")
    void removingPreviouslyTerminalListingSafelyOverridesClosureWithoutTouchingJobs(
            CollabListingStatus initialStatus,
            CollabClosureReason initialReason) {
        User owner = user(UUID.randomUUID().toString());
        User reporter = user(UUID.randomUUID().toString());
        User admin = user(UUID.randomUUID().toString());
        Collab listing = listing(owner);
        listing.setStatus(initialStatus);
        listing.setClosureReason(initialReason);
        listing.setClosedAt(NOW.minusSeconds(300));
        CollabReport report = report(listing, reporter);
        when(reportRepository.findListingId(report.getId())).thenReturn(Optional.of(listing.getId()));
        when(listingRepository.findByIdForUpdate(listing.getId())).thenReturn(Optional.of(listing));
        when(reportRepository.findByIdForUpdate(report.getId())).thenReturn(Optional.of(report));
        when(applicationRepository.findByListingIdAndStatus(
                listing.getId(), CollabApplicationStatus.PENDING)).thenReturn(List.of());
        when(userFinder.getUser(admin.getId())).thenReturn(admin);

        CollabReportAdminResponse response = service.review(admin.getId(), report.getId(),
                new CollabReportReviewRequest(CollabReportDecision.REMOVE_LISTING, 0L,
                        "Topluluk kurallarını ihlal ediyor."));

        assertThat(report.getStatus()).isEqualTo(CollabReportStatus.ACTIONED);
        assertThat(listing.getStatus()).isEqualTo(CollabListingStatus.CLOSED);
        assertThat(listing.getClosureReason()).isEqualTo(CollabClosureReason.ADMIN_REMOVED);
        assertThat(response.listingStatus()).isEqualTo(CollabListingStatus.CLOSED);
        assertThat(response.listingStatusAtReport()).isEqualTo(initialStatus);
        verify(applicationRepository).invalidatePending(listing.getId(), null,
                CollabApplicationStatus.PENDING,
                CollabApplicationStatus.INVALIDATED_BY_LISTING_CLOSURE, NOW);
        verify(savedListingRepository).deleteByListingId(listing.getId());
        verify(eventPublisher, org.mockito.Mockito.times(2)).publishEvent(any(Object.class));
    }

    @Test
    void removingOneReportCoResolvesEveryOpenSiblingInTheSameTransaction() {
        User owner = user(UUID.randomUUID().toString());
        User firstReporter = user(UUID.randomUUID().toString());
        User secondReporter = user(UUID.randomUUID().toString());
        User admin = user(UUID.randomUUID().toString());
        Collab listing = listing(owner);
        CollabReport first = report(listing, firstReporter);
        CollabReport sibling = report(listing, secondReporter);
        when(reportRepository.findListingId(first.getId())).thenReturn(Optional.of(listing.getId()));
        when(listingRepository.findByIdForUpdate(listing.getId())).thenReturn(Optional.of(listing));
        when(reportRepository.findByIdForUpdate(first.getId())).thenReturn(Optional.of(first));
        when(reportRepository.findByListingIdAndStatusForUpdate(
                listing.getId(), CollabReportStatus.OPEN)).thenReturn(List.of(first, sibling));
        when(applicationRepository.findByListingIdAndStatus(
                listing.getId(), CollabApplicationStatus.PENDING)).thenReturn(List.of());
        when(userFinder.getUser(admin.getId())).thenReturn(admin);

        service.review(admin.getId(), first.getId(),
                new CollabReportReviewRequest(CollabReportDecision.REMOVE_LISTING, 0L,
                        "Aynı ihlale ilişkin raporlar birlikte çözüldü."));

        assertThat(List.of(first, sibling))
                .allSatisfy(value -> {
                    assertThat(value.getStatus()).isEqualTo(CollabReportStatus.ACTIONED);
                    assertThat(value.getReviewDecision()).isEqualTo(CollabReportDecision.REMOVE_LISTING);
                    assertThat(value.getReviewedByUser()).isSameAs(admin);
                    assertThat(value.getReviewedAt()).isEqualTo(NOW);
                    assertThat(value.getResolutionNote())
                            .isEqualTo("Aynı ihlale ilişkin raporlar birlikte çözüldü.");
                });
        ArgumentCaptor<CollabNotificationEvent> captor = ArgumentCaptor.forClass(CollabNotificationEvent.class);
        verify(eventPublisher, org.mockito.Mockito.times(3)).publishEvent(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(CollabNotificationEvent::recipientId)
                .containsExactlyInAnyOrder(owner.getId(), firstReporter.getId(), secondReporter.getId());
    }

    @Test
    void alreadyAdminRemovedListingActionsNewReportWithoutDuplicateCleanupOrOwnerNotification() {
        User owner = user(UUID.randomUUID().toString());
        User reporter = user(UUID.randomUUID().toString());
        User admin = user(UUID.randomUUID().toString());
        Collab listing = listing(owner);
        listing.setStatus(CollabListingStatus.CLOSED);
        listing.setClosureReason(CollabClosureReason.ADMIN_REMOVED);
        listing.setClosedAt(NOW.minusSeconds(300));
        CollabReport report = report(listing, reporter);
        when(reportRepository.findListingId(report.getId())).thenReturn(Optional.of(listing.getId()));
        when(listingRepository.findByIdForUpdate(listing.getId())).thenReturn(Optional.of(listing));
        when(reportRepository.findByIdForUpdate(report.getId())).thenReturn(Optional.of(report));
        when(userFinder.getUser(admin.getId())).thenReturn(admin);

        service.review(admin.getId(), report.getId(),
                new CollabReportReviewRequest(CollabReportDecision.REMOVE_LISTING, 0L,
                        "Daha önce uygulanan kaldırma kararıyla ilişkilendirildi."));

        assertThat(report.getStatus()).isEqualTo(CollabReportStatus.ACTIONED);
        verify(applicationRepository, never()).findByListingIdAndStatus(any(), any());
        verify(applicationRepository, never()).invalidatePending(any(), any(), any(), any(), any());
        verify(savedListingRepository, never()).deleteByListingId(any());
        verify(listingRepository, never()).flush();
        ArgumentCaptor<CollabNotificationEvent> captor = ArgumentCaptor.forClass(CollabNotificationEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().recipientId()).isEqualTo(reporter.getId());
    }

    @Test
    void dismissingReportDoesNotMutateListing() {
        User owner = user("00000000-0000-0000-0000-000000000011");
        User reporter = user("00000000-0000-0000-0000-000000000012");
        User admin = user("00000000-0000-0000-0000-000000000013");
        Collab listing = listing(owner);
        listing.setStatus(CollabListingStatus.EXPIRED);
        listing.setClosureReason(CollabClosureReason.EXPIRED);
        listing.setClosedAt(NOW.minusSeconds(60));
        CollabReport report = report(listing, reporter);
        when(reportRepository.findByIdForUpdate(report.getId())).thenReturn(Optional.of(report));
        when(userFinder.getUser(admin.getId())).thenReturn(admin);

        service.review(admin.getId(), report.getId(),
                new CollabReportReviewRequest(CollabReportDecision.DISMISS, 0L,
                        "İhlal tespit edilmedi."));

        assertThat(report.getStatus()).isEqualTo(CollabReportStatus.DISMISSED);
        assertThat(listing.getStatus()).isEqualTo(CollabListingStatus.EXPIRED);
        assertThat(listing.getClosureReason()).isEqualTo(CollabClosureReason.EXPIRED);
        verify(listingRepository, never()).findByIdForUpdate(any());
        verify(applicationRepository, never()).invalidatePending(any(), any(), any(), any(), any());
        verify(savedListingRepository, never()).deleteByListingId(any());
    }

    @Test
    void staleReviewIsRejectedBeforeListingMutation() {
        Collab listing = listing(user("00000000-0000-0000-0000-000000000021"));
        CollabReport report = report(listing,
                user("00000000-0000-0000-0000-000000000022"));
        report.setVersion(4L);
        when(reportRepository.findListingId(report.getId())).thenReturn(Optional.of(listing.getId()));
        when(listingRepository.findByIdForUpdate(listing.getId())).thenReturn(Optional.of(listing));
        when(reportRepository.findByIdForUpdate(report.getId())).thenReturn(Optional.of(report));

        assertThatThrownBy(() -> service.review(UUID.randomUUID(), report.getId(),
                new CollabReportReviewRequest(CollabReportDecision.REMOVE_LISTING, 3L,
                        "Geçerli moderasyon açıklaması.")))
                .isInstanceOfSatisfying(SoundConnectException.class,
                        error -> assertThat(error.getErrorType()).isEqualTo(ErrorType.COLLAB_STALE_UPDATE));

        verify(applicationRepository, never()).invalidatePending(any(), any(), any(), any(), any());
        verify(savedListingRepository, never()).deleteByListingId(any());
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void exactTerminalReplayIsIdempotent() {
        Collab listing = listing(user("00000000-0000-0000-0000-000000000031"));
        listing.setInstrument(null);
        listing.setBranch(CollabBranch.OTHER);
        listing.setCustomSpecialty("Canli loop sanatcisi");
        CollabReport report = report(listing,
                user("00000000-0000-0000-0000-000000000032"));
        report.setStatus(CollabReportStatus.DISMISSED);
        report.setReviewDecision(CollabReportDecision.DISMISS);
        report.setResolutionNote("İhlal tespit edilmedi.");
        when(reportRepository.findByIdForUpdate(report.getId())).thenReturn(Optional.of(report));

        var replay = service.review(UUID.randomUUID(), report.getId(),
                new CollabReportReviewRequest(CollabReportDecision.DISMISS, 0L,
                        "  İhlal tespit edilmedi.  "));

        assertThat(replay.status()).isEqualTo(CollabReportStatus.DISMISSED);
        assertListingEvidence(replay, report);
        verify(listingRepository, never()).findByIdForUpdate(any());
        verify(userFinder, never()).getUser(any());
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    private static Stream<Arguments> terminalListingCases() {
        return Stream.of(
                Arguments.of(CollabListingStatus.CLOSED, CollabClosureReason.OWNER_CLOSED),
                Arguments.of(CollabListingStatus.EXPIRED, CollabClosureReason.EXPIRED),
                Arguments.of(CollabListingStatus.CLOSED, CollabClosureReason.MATCHED));
    }

    private static Collab listing(User owner) {
        CollabActor publisher = CollabActor.builder()
                .displayName("Test Sahnesi")
                .build();
        publisher.setId(UUID.fromString("00000000-0000-0000-0000-000000000041"));
        Instrument instrument = Instrument.builder()
                .name("Gitar")
                .build();
        instrument.setId(UUID.fromString("00000000-0000-0000-0000-000000000042"));
        City city = City.builder()
                .name("Istanbul")
                .build();
        city.setId(UUID.fromString("00000000-0000-0000-0000-000000000043"));
        Collab listing = Collab.builder()
                .owner(owner)
                .publisherActor(publisher)
                .cadence(CollabCadence.EXTRA)
                .wantedType(CollabWantedType.MUSICIAN)
                .instrument(instrument)
                .title("Test ilanı")
                .description("Aksam programi icin deneyimli gitarist ariyoruz.")
                .city(city)
                .genres(new ArrayList<>(List.of("Rock", "Jazz")))
                .scheduledAt(NOW.plusSeconds(7_200))
                .feeAmountMinor(250_000L)
                .currency("TRY")
                .status(CollabListingStatus.OPEN)
                .build();
        listing.setId(UUID.randomUUID());
        return listing;
    }

    private static CollabReport report(Collab listing, User reporter) {
        CollabReport report = CollabReport.builder()
                .listing(listing)
                .reporterUser(reporter)
                .reason(CollabReportReason.SPAM)
                .reportedAt(NOW)
                .listingEvidence(CollabReportListingEvidence.capture(listing))
                .status(CollabReportStatus.OPEN)
                .build();
        report.setId(UUID.randomUUID());
        return report;
    }

    private static void assertListingEvidence(CollabReportAdminResponse response, CollabReport report) {
        Collab listing = report.getListing();
        CollabReportListingEvidence evidence = report.getListingEvidence();
        assertThat(response.listingId()).isEqualTo(listing.getId());
        assertThat(response.listingTitle()).isEqualTo(evidence.title());
        assertThat(response.listingDescription()).isEqualTo(evidence.description());
        assertThat(response.listingStatus()).isEqualTo(listing.getStatus());
        assertThat(response.listingStatusAtReport()).isEqualTo(evidence.listingStatus());
        assertThat(response.publisherActorId()).isEqualTo(evidence.publisherActorId());
        assertThat(response.publisherDisplayName()).isEqualTo(evidence.publisherDisplayName());
        assertThat(response.cadence()).isEqualTo(evidence.cadence());
        assertThat(response.wantedType()).isEqualTo(evidence.wantedType());
        if (evidence.instrumentId() == null) {
            assertThat(response.instrument()).isNull();
        } else {
            assertThat(response.instrument().id()).isEqualTo(evidence.instrumentId());
            assertThat(response.instrument().name()).isEqualTo(evidence.instrumentName());
        }
        assertThat(response.branch()).isEqualTo(evidence.branch());
        assertThat(response.customSpecialty()).isEqualTo(evidence.customSpecialty());
        assertThat(response.city().id()).isEqualTo(evidence.cityId());
        assertThat(response.city().name()).isEqualTo(evidence.cityName());
        assertThat(response.listingGenres()).containsExactlyElementsOf(evidence.genres());
        assertThat(response.scheduledAt()).isEqualTo(evidence.scheduledAt());
        assertThat(response.feeAmountMinor()).isEqualTo(evidence.feeAmountMinor());
        assertThat(response.currency()).isEqualTo(evidence.currency());
    }

    private static User user(String id) {
        User user = User.builder().build();
        user.setId(UUID.fromString(id));
        return user;
    }
}
