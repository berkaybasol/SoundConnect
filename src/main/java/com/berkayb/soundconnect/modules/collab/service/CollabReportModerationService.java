package com.berkayb.soundconnect.modules.collab.service;

import com.berkayb.soundconnect.modules.collab.dto.request.CollabReportReviewRequest;
import com.berkayb.soundconnect.modules.collab.dto.response.CollabReportAdminResponse;
import com.berkayb.soundconnect.modules.collab.dto.response.CollabCitySummary;
import com.berkayb.soundconnect.modules.collab.dto.response.CollabInstrumentSummary;
import com.berkayb.soundconnect.modules.collab.entity.Collab;
import com.berkayb.soundconnect.modules.collab.entity.CollabApplication;
import com.berkayb.soundconnect.modules.collab.entity.CollabReport;
import com.berkayb.soundconnect.modules.collab.entity.CollabReportListingEvidence;
import com.berkayb.soundconnect.modules.collab.enums.CollabApplicationStatus;
import com.berkayb.soundconnect.modules.collab.enums.CollabClosureReason;
import com.berkayb.soundconnect.modules.collab.enums.CollabListingStatus;
import com.berkayb.soundconnect.modules.collab.enums.CollabReportDecision;
import com.berkayb.soundconnect.modules.collab.enums.CollabReportReason;
import com.berkayb.soundconnect.modules.collab.enums.CollabReportStatus;
import com.berkayb.soundconnect.modules.collab.event.CollabNotificationEvent;
import com.berkayb.soundconnect.modules.collab.repository.CollabApplicationRepository;
import com.berkayb.soundconnect.modules.collab.repository.CollabReportRepository;
import com.berkayb.soundconnect.modules.collab.repository.CollabRepository;
import com.berkayb.soundconnect.modules.collab.repository.CollabSavedListingRepository;
import com.berkayb.soundconnect.modules.collab.support.CollabTimeProvider;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.support.UserEntityFinder;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.response.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CollabReportModerationService {
    private static final int MAX_PAGE_SIZE = 50;
    private static final int MAX_PAGE_NUMBER = 1_000;

    private final CollabReportRepository reportRepository;
    private final CollabRepository listingRepository;
    private final CollabApplicationRepository applicationRepository;
    private final CollabSavedListingRepository savedListingRepository;
    private final UserEntityFinder userFinder;
    private final CollabTimeProvider timeProvider;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional(readOnly = true)
    public PageResponse<CollabReportAdminResponse> list(CollabReportStatus status,
                                                        CollabReportReason reason,
                                                        int page,
                                                        int size) {
        if (page < 0 || page > MAX_PAGE_NUMBER || size < 1 || size > MAX_PAGE_SIZE) {
            throw new SoundConnectException(ErrorType.COLLAB_PAGE_REQUEST_INVALID);
        }
        PageRequest pageable = PageRequest.of(page, size,
                Sort.by(Sort.Order.desc("reportedAt"), Sort.Order.desc("id")));
        Page<CollabReport> reports = reportRepository.findAdminPage(status, reason, pageable);
        return PageResponse.from(reports.map(this::response));
    }

    @Transactional
    public CollabReportAdminResponse review(UUID adminUserId,
                                            UUID reportId,
                                            CollabReportReviewRequest request) {
        String resolutionNote = normalizeResolutionNote(request.resolutionNote());
        Collab listing = null;
        if (request.decision() == CollabReportDecision.REMOVE_LISTING) {
            UUID listingId = reportRepository.findListingId(reportId)
                    .orElseThrow(() -> new SoundConnectException(ErrorType.COLLAB_REPORT_NOT_FOUND));
            listing = listingRepository.findByIdForUpdate(listingId)
                    .orElseThrow(() -> new SoundConnectException(ErrorType.COLLAB_NOT_FOUND));
        }
        CollabReport report = reportRepository.findByIdForUpdate(reportId)
                .orElseThrow(() -> new SoundConnectException(ErrorType.COLLAB_REPORT_NOT_FOUND));
        CollabReportStatus targetStatus = request.decision() == CollabReportDecision.DISMISS
                ? CollabReportStatus.DISMISSED
                : CollabReportStatus.ACTIONED;

        if (report.getStatus() != CollabReportStatus.OPEN) {
            if (report.getStatus() == targetStatus
                    && report.getReviewDecision() == request.decision()
                    && Objects.equals(report.getResolutionNote(), resolutionNote)) {
                if (request.decision() == CollabReportDecision.REMOVE_LISTING) {
                    reconcileRemovalReplay(report, listing, resolutionNote, adminUserId);
                }
                return response(report);
            }
            throw new SoundConnectException(ErrorType.COLLAB_REPORT_STATUS_INVALID);
        }
        if (report.getVersion() != request.expectedVersion()) {
            throw new SoundConnectException(ErrorType.COLLAB_STALE_UPDATE);
        }

        Instant now = timeProvider.now();
        if (request.decision() == CollabReportDecision.REMOVE_LISTING) {
            removeListing(listing, now);
        }

        User reviewer = userFinder.getUser(adminUserId);
        List<CollabReport> resolvedReports;
        if (request.decision() == CollabReportDecision.REMOVE_LISTING) {
            resolvedReports = openReportsForRemoval(report);
        } else {
            resolvedReports = List.of(report);
        }
        resolvedReports.forEach(value -> resolve(
                value, targetStatus, request.decision(), reviewer, now, resolutionNote));
        reportRepository.flush();

        Collab resolvedListing = report.getListing();
        resolvedReports.forEach(value -> publishReportResolved(
                value, resolvedListing.getId(), request.decision(), now));
        return response(report);
    }

    private static String normalizeResolutionNote(String value) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.length() < 5 || normalized.length() > 500) {
            throw new SoundConnectException(ErrorType.VALIDATION_ERROR);
        }
        return normalized;
    }

    private void removeListing(Collab listing, Instant now) {
        if (listing.getStatus() == CollabListingStatus.CLOSED
                && listing.getClosureReason() == CollabClosureReason.ADMIN_REMOVED) return;
        List<CollabApplication> pending = applicationRepository.findByListingIdAndStatus(
                listing.getId(), CollabApplicationStatus.PENDING);
        listing.setStatus(CollabListingStatus.CLOSED);
        listing.setClosureReason(CollabClosureReason.ADMIN_REMOVED);
        listing.setClosedAt(now);
        applicationRepository.invalidatePending(listing.getId(), null,
                CollabApplicationStatus.PENDING,
                CollabApplicationStatus.INVALIDATED_BY_LISTING_CLOSURE,
                now);
        savedListingRepository.deleteByListingId(listing.getId());
        listingRepository.flush();

        publish(listing.getOwner().getId(), NotificationType.COLLAB_LISTING_REMOVED,
                "Collab ilanın kaldırıldı",
                "İlanın moderasyon incelemesi sonucunda yayından kaldırıldı.",
                "LISTING_REMOVED", Map.of("listingId", listing.getId()), now);
        pending.forEach(application -> publish(application.getApplicantUser().getId(),
                NotificationType.COLLAB_APPLICATION_INVALIDATED,
                "Başvuru geçersizleşti",
                "Başvurduğun ilan moderasyon sonucunda kaldırıldı.",
                "APPLICATION_INVALIDATED",
                Map.of("listingId", listing.getId(), "applicationId", application.getId()), now));
    }

    private List<CollabReport> openReportsForRemoval(CollabReport selected) {
        Map<UUID, CollabReport> unique = new LinkedHashMap<>();
        unique.put(selected.getId(), selected);
        reportRepository.findByListingIdAndStatusForUpdate(
                        selected.getListing().getId(), CollabReportStatus.OPEN)
                .forEach(value -> unique.put(value.getId(), value));
        return List.copyOf(unique.values());
    }

    private void reconcileRemovalReplay(CollabReport selected,
                                        Collab listing,
                                        String resolutionNote,
                                        UUID adminUserId) {
        Instant now = timeProvider.now();
        removeListing(listing, now);
        List<CollabReport> siblings = reportRepository.findByListingIdAndStatusForUpdate(
                listing.getId(), CollabReportStatus.OPEN);
        if (siblings.isEmpty()) return;
        User reviewer = selected.getReviewedByUser() == null
                ? userFinder.getUser(adminUserId)
                : selected.getReviewedByUser();
        Instant reviewedAt = selected.getReviewedAt();
        siblings.forEach(value -> resolve(value, CollabReportStatus.ACTIONED,
                CollabReportDecision.REMOVE_LISTING, reviewer,
                reviewedAt == null ? now : reviewedAt, resolutionNote));
        reportRepository.flush();
        siblings.forEach(value -> publishReportResolved(
                value, listing.getId(), CollabReportDecision.REMOVE_LISTING, now));
    }

    private void resolve(CollabReport report,
                         CollabReportStatus status,
                         CollabReportDecision decision,
                         User reviewer,
                         Instant reviewedAt,
                         String resolutionNote) {
        report.setStatus(status);
        report.setReviewDecision(decision);
        report.setReviewedByUser(reviewer);
        report.setReviewedAt(reviewedAt);
        report.setResolutionNote(resolutionNote);
    }

    private void publishReportResolved(CollabReport report,
                                       UUID listingId,
                                       CollabReportDecision decision,
                                       Instant occurredAt) {
        publish(report.getReporterUser().getId(), NotificationType.COLLAB_REPORT_RESOLVED,
                "Bildirimin sonuçlandırıldı",
                decision == CollabReportDecision.REMOVE_LISTING
                        ? "Bildirdiğin Collab ilanı kaldırıldı."
                        : "Collab bildirimin moderasyon ekibi tarafından incelendi.",
                "REPORT_RESOLVED",
                Map.of("listingId", listingId, "reportId", report.getId(),
                        "decision", decision.name()), occurredAt);
    }

    private CollabReportAdminResponse response(CollabReport report) {
        Collab listing = report.getListing();
        CollabReportListingEvidence evidence = report.getListingEvidence() == null
                ? CollabReportListingEvidence.capture(listing)
                : report.getListingEvidence();
        CollabInstrumentSummary instrument = evidence.instrumentId() == null ? null
                : new CollabInstrumentSummary(evidence.instrumentId(), evidence.instrumentName());
        return new CollabReportAdminResponse(
                report.getId(), report.getVersion(), report.getStatus(), report.getReason(),
                report.getDetails(), report.getReportedAt(), listing.getId(), evidence.title(),
                evidence.description(), listing.getStatus(), evidence.listingStatus(), evidence.publisherActorId(),
                evidence.publisherDisplayName(), evidence.cadence(), evidence.wantedType(),
                instrument, evidence.branch(), evidence.customSpecialty(),
                new CollabCitySummary(evidence.cityId(), evidence.cityName()),
                evidence.genres(), evidence.scheduledAt(), evidence.feeAmountMinor(), evidence.currency(),
                report.getReporterUser().getId(), report.getReviewDecision(),
                report.getReviewedByUser() == null ? null : report.getReviewedByUser().getId(),
                report.getReviewedAt(), report.getResolutionNote());
    }

    private void publish(UUID recipientId,
                         NotificationType type,
                         String title,
                         String message,
                         String action,
                         Map<String, ?> attributes,
                         Instant occurredAt) {
        eventPublisher.publishEvent(CollabNotificationEvent.create(
                recipientId, type, title, message, action, attributes, occurredAt));
    }
}
