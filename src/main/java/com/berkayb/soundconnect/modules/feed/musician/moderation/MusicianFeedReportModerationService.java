package com.berkayb.soundconnect.modules.feed.musician.moderation;

import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedItemType;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
@PreAuthorize("hasAuthority('MANAGE_MUSICIAN_FEED_REPORTS')")
public class MusicianFeedReportModerationService {
    private final MusicianFeedReportModerationRepository reports;
    private final MusicianFeedReportAuditRepository audits;
    private final MusicianFeedModerationPolicy policy;
    private final MusicianFeedReportCursorCodec cursors;
    private final Clock clock;

    @Autowired
    public MusicianFeedReportModerationService(MusicianFeedReportModerationRepository reports,
            MusicianFeedReportAuditRepository audits, MusicianFeedModerationPolicy policy,
            MusicianFeedReportCursorCodec cursors) {
        this(reports, audits, policy, cursors, Clock.systemUTC());
    }

    MusicianFeedReportModerationService(MusicianFeedReportModerationRepository reports,
            MusicianFeedReportAuditRepository audits, MusicianFeedModerationPolicy policy,
            MusicianFeedReportCursorCodec cursors, Clock clock) {
        this.reports = reports;
        this.audits = audits;
        this.policy = policy;
        this.cursors = cursors;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public MusicianFeedReportPage list(UUID viewer, MusicianFeedReportStatus requestedStatus,
            MusicianFeedItemType itemType, Integer requestedLimit, String cursor) {
        requireViewer(viewer);
        int limit = requestedLimit == null ? 20 : requestedLimit;
        if (limit < 1 || limit > 50) throw invalid();
        MusicianFeedReportStatus status = requestedStatus == null ? MusicianFeedReportStatus.NEW : requestedStatus;
        Instant now = now();
        var position = cursor == null ? null : cursors.decode(cursor, viewer, status, itemType, now);
        Instant anchor = position == null ? now : position.anchor();
        var values = reports.page(status, itemType, anchor, position, limit + 1);
        boolean hasMore = values.size() > limit;
        var items = List.copyOf(values.subList(0, Math.min(limit, values.size())));
        String next = null;
        if (hasMore) {
            var last = items.getLast();
            next = cursors.encode(viewer, status, itemType,
                    new MusicianFeedReportCursorCodec.Position(anchor, last.reportedAt(), last.id()));
        }
        return new MusicianFeedReportPage(items, next, hasMore);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MusicianFeedReportDetail detail(UUID viewer, UUID reportId) {
        requireViewer(viewer);
        return detail(requireReport(reportId, false));
    }

    @Transactional
    public MusicianFeedReportDetail review(UUID viewer, UUID reportId, MusicianFeedReportReviewRequest request) {
        requireViewer(viewer);
        String note = normalizedNote(request);
        String hash = requestHash(viewer, request, note);
        // Policy writes only this report's restriction; no sibling report lock
        // is acquired. Version, restriction and audit commit in this transaction.
        var report = requireReport(reportId, true);
        var prior = audits.requestHash(reportId, request.clientRequestId());
        if (prior.isPresent()) {
            if (!prior.get().equals(hash)) throw conflict();
            return detail(report);
        }
        if (report.summary().version() != request.expectedVersion()) throw conflict();
        var subject = report.subject();
        if (!allowed(report.summary().status(), policy.describe(subject), policy.isRestricted(subject))
                .contains(request.decision())) throw conflict();
        MusicianFeedReportStatus next = switch (request.decision()) {
            case START_REVIEW -> MusicianFeedReportStatus.REVIEWING;
            case DISMISS -> MusicianFeedReportStatus.DISMISSED;
            case REMOVE_FROM_FEED -> MusicianFeedReportStatus.ACTIONED;
            case RESTORE_TO_FEED -> MusicianFeedReportStatus.RESTORED;
        };
        Instant now = now();
        if (request.decision() == MusicianFeedReportDecision.REMOVE_FROM_FEED) policy.remove(subject, viewer, now);
        if (request.decision() == MusicianFeedReportDecision.RESTORE_TO_FEED) policy.restore(subject, viewer, now);
        reports.transition(reportId, report.summary().version(), next, request.decision(), viewer, note, now);
        audits.append(reportId, request.clientRequestId(), hash, report.summary().version() + 1,
                request.decision(), report.summary().status(), next, viewer, note, now);
        return detail(requireReport(reportId, false));
    }

    private MusicianFeedReportDetail detail(MusicianFeedReportModerationRepository.ReportRecord report) {
        var subject = report.subject();
        String description = policy.describe(subject);
        return new MusicianFeedReportDetail(report.summary(), report.reporterUserId(), report.evidence(),
                description, allowed(report.summary().status(), description, policy.isRestricted(subject)),
                policy.isSubjectRestricted(subject), audits.history(report.summary().id()));
    }

    private List<MusicianFeedReportDecision> allowed(MusicianFeedReportStatus status, String scope,
                                                   boolean ownRestriction) {
        List<MusicianFeedReportDecision> result = new ArrayList<>();
        if (status == MusicianFeedReportStatus.NEW) result.add(MusicianFeedReportDecision.START_REVIEW);
        if (status == MusicianFeedReportStatus.NEW || status == MusicianFeedReportStatus.REVIEWING) {
            result.add(MusicianFeedReportDecision.DISMISS);
            if (scope != null) result.add(MusicianFeedReportDecision.REMOVE_FROM_FEED);
        }
        if (status == MusicianFeedReportStatus.ACTIONED && ownRestriction) {
            result.add(MusicianFeedReportDecision.RESTORE_TO_FEED);
        }
        return List.copyOf(result);
    }

    private MusicianFeedReportModerationRepository.ReportRecord requireReport(UUID id, boolean lock) {
        if (id == null) throw invalid();
        return reports.find(id, lock).orElseThrow(() ->
                new SoundConnectException(ErrorType.MUSICIAN_FEED_REPORT_NOT_FOUND));
    }

    private String normalizedNote(MusicianFeedReportReviewRequest request) {
        if (request == null || request.clientRequestId() == null || request.expectedVersion() == null
                || request.expectedVersion() < 0 || request.decision() == null || request.resolutionNote() == null) {
            throw invalid();
        }
        String note = request.resolutionNote().strip();
        if (note.length() < 5 || note.length() > 500 || note.codePoints().anyMatch(value ->
                (Character.isISOControl(value) && value != '\n' && value != '\t')
                        || (value >= 0xd800 && value <= 0xdfff))) throw invalid();
        return note;
    }

    private String requestHash(UUID actor, MusicianFeedReportReviewRequest request, String note) {
        String canonical = actor + "\u0000" + request.expectedVersion() + "\u0000" + request.decision() + "\u0000" + note;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private void requireViewer(UUID viewer) {
        if (viewer == null) throw new SoundConnectException(ErrorType.UNAUTHORIZED);
    }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
    private SoundConnectException invalid() { return new SoundConnectException(ErrorType.BAD_REQUEST); }
    private SoundConnectException conflict() { return new SoundConnectException(ErrorType.MUSICIAN_FEED_REPORT_CONFLICT); }
}
