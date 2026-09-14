package com.berkayb.soundconnect.modules.feed.musician.feedback;

import com.berkayb.soundconnect.modules.feed.musician.api.*;
import com.berkayb.soundconnect.modules.feed.musician.core.MusicianFeedViewerGuard;
import com.berkayb.soundconnect.modules.feed.musician.core.BackstageFeedAudience;
import com.berkayb.soundconnect.modules.feed.musician.candidate.MusicianFeedCandidate;
import com.berkayb.soundconnect.modules.feed.musician.delivery.MusicianFeedDeliveredItem;
import com.berkayb.soundconnect.modules.feed.musician.delivery.MusicianFeedDeliveryService;
import com.berkayb.soundconnect.modules.analytics.AnnouncementAnalyticsStore;
import com.berkayb.soundconnect.modules.promotion.announcement.AnnouncementAccess;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class MusicianFeedFeedbackService {
    private static final Set<MusicianFeedFeedbackAction> ITEM_ACTIONS = Set.of(
            MusicianFeedFeedbackAction.HIDE,
            MusicianFeedFeedbackAction.SHOW_LESS,
            MusicianFeedFeedbackAction.REPORT);

    private final MusicianFeedFeedbackRepository repository;
    private final MusicianFeedViewerGuard viewerGuard;
    private final MusicianFeedAuthorProfileGuard authorProfiles;
    private final MusicianFeedDeliveryService deliveries;
    private final MusicianFeedReportDispatcher reports;
    private final MusicianFeedFeedbackLock feedbackLock;
    private final MusicianFeedFeedbackReader reader;
    private final Clock clock;
    private final AnnouncementAnalyticsStore announcementAnalytics;
    private final AnnouncementAccess announcementAccess;

    public MusicianFeedFeedbackService(
            MusicianFeedFeedbackRepository repository,
            MusicianFeedViewerGuard viewerGuard,
            MusicianFeedAuthorProfileGuard authorProfiles,
            MusicianFeedDeliveryService deliveries,
            MusicianFeedReportDispatcher reports,
            MusicianFeedFeedbackLock feedbackLock,
            MusicianFeedFeedbackReader reader
    ) {
        this(repository, viewerGuard, authorProfiles, deliveries, reports, feedbackLock, reader, Clock.systemUTC());
    }

    public MusicianFeedFeedbackService(MusicianFeedFeedbackRepository repository,
                                       MusicianFeedViewerGuard viewerGuard,
                                       MusicianFeedAuthorProfileGuard authorProfiles,
                                       MusicianFeedDeliveryService deliveries,
                                       MusicianFeedReportDispatcher reports,
                                       MusicianFeedFeedbackLock feedbackLock,
                                       MusicianFeedFeedbackReader reader,
                                       AnnouncementAnalyticsStore announcementAnalytics) {
        this(repository, viewerGuard, authorProfiles, deliveries, reports, feedbackLock, reader,
                Clock.systemUTC(), announcementAnalytics);
    }

    @Autowired
    public MusicianFeedFeedbackService(MusicianFeedFeedbackRepository repository,
                                       MusicianFeedViewerGuard viewerGuard,
                                       MusicianFeedAuthorProfileGuard authorProfiles,
                                       MusicianFeedDeliveryService deliveries,
                                       MusicianFeedReportDispatcher reports,
                                       MusicianFeedFeedbackLock feedbackLock,
                                       MusicianFeedFeedbackReader reader,
                                       AnnouncementAnalyticsStore announcementAnalytics,
                                       AnnouncementAccess announcementAccess) {
        this(repository, viewerGuard, authorProfiles, deliveries, reports, feedbackLock, reader,
                Clock.systemUTC(), announcementAnalytics, announcementAccess);
    }

    MusicianFeedFeedbackService(
            MusicianFeedFeedbackRepository repository,
            MusicianFeedViewerGuard viewerGuard,
            MusicianFeedAuthorProfileGuard authorProfiles,
            MusicianFeedDeliveryService deliveries,
            MusicianFeedReportDispatcher reports,
            MusicianFeedFeedbackLock feedbackLock,
            MusicianFeedFeedbackReader reader,
            Clock clock
    ) {
        this(repository, viewerGuard, authorProfiles, deliveries, reports, feedbackLock, reader, clock, null);
    }

    MusicianFeedFeedbackService(MusicianFeedFeedbackRepository repository,
                                MusicianFeedViewerGuard viewerGuard, MusicianFeedAuthorProfileGuard authorProfiles,
                                MusicianFeedDeliveryService deliveries, MusicianFeedReportDispatcher reports,
                                MusicianFeedFeedbackLock feedbackLock, MusicianFeedFeedbackReader reader,
                                Clock clock, AnnouncementAnalyticsStore announcementAnalytics) {
        this(repository, viewerGuard, authorProfiles, deliveries, reports, feedbackLock, reader,
                clock, announcementAnalytics, null);
    }

    MusicianFeedFeedbackService(MusicianFeedFeedbackRepository repository,
                                MusicianFeedViewerGuard viewerGuard, MusicianFeedAuthorProfileGuard authorProfiles,
                                MusicianFeedDeliveryService deliveries, MusicianFeedReportDispatcher reports,
                                MusicianFeedFeedbackLock feedbackLock, MusicianFeedFeedbackReader reader,
                                Clock clock, AnnouncementAnalyticsStore announcementAnalytics,
                                AnnouncementAccess announcementAccess) {
        this.repository = repository;
        this.viewerGuard = viewerGuard;
        this.authorProfiles = authorProfiles;
        this.deliveries = deliveries;
        this.reports = reports;
        this.feedbackLock = feedbackLock;
        this.reader = reader;
        this.clock = clock;
        this.announcementAnalytics = announcementAnalytics;
        this.announcementAccess = announcementAccess;
    }

    @Transactional(readOnly = true)
    public MusicianFeedFeedbackSnapshot snapshot(UUID viewerUserId) {
        if (viewerUserId == null) throw new SoundConnectException(ErrorType.UNAUTHORIZED);
        return reader.ranking(viewerUserId);
    }

    @Transactional(readOnly = true)
    public MusicianFeedFeedbackSnapshot forCandidates(
            UUID viewerUserId,
            MusicianFeedFeedbackSnapshot ranking,
            Collection<MusicianFeedCandidate> organic,
            Collection<MusicianFeedCandidate> promotions
    ) {
        if (viewerUserId == null) throw new SoundConnectException(ErrorType.UNAUTHORIZED);
        return reader.forCandidates(viewerUserId, ranking, organic, promotions);
    }

    @Transactional
    public MusicianFeedFeedbackResponse recordItem(
            UUID viewerUserId,
            String itemId,
            MusicianFeedFeedbackRequest request
    ) {
        viewerGuard.requireMusicianProfile(viewerUserId);
        return recordItemAuthorized(viewerUserId, itemId, request);
    }

    @Transactional
    public MusicianFeedFeedbackResponse recordItemForVenue(
            UUID viewerUserId, String itemId, MusicianFeedFeedbackRequest request) {
        viewerGuard.requireVenueProfile(viewerUserId);
        return recordItemAuthorized(viewerUserId, itemId, request);
    }

    private MusicianFeedFeedbackResponse recordItemAuthorized(
            UUID viewerUserId, String itemId, MusicianFeedFeedbackRequest request) {
        return recordItemAuthorized(viewerUserId, itemId, request, null);
    }

    private MusicianFeedFeedbackResponse recordItemAuthorized(
            UUID viewerUserId, String itemId, MusicianFeedFeedbackRequest request, BackstageFeedAudience expectedAudience) {
        if (request == null || !ITEM_ACTIONS.contains(request.action())
                || request.impressionToken() == null || request.impressionToken().isBlank()) throw invalid();
        Instant now = now();
        MusicianFeedDeliveredItem delivery = deliveries.require(
                request.impressionToken(), viewerUserId, itemId, now);
        if (expectedAudience != null && !expectedAudience.algorithmVersion().equals(delivery.algorithmVersion())) throw invalid();
        if (!delivery.feedbackCapabilities().contains(request.action())) throw invalid();
        MusicianFeedItemType itemType = delivery.itemType();
        String normalizedReason = normalizeReason(request.reason());
        String scope = "ITEM:" + itemId;
        feedbackLock.viewer(viewerUserId);
        if (itemType == MusicianFeedItemType.ANNOUNCEMENT) {
            // Hold current promotion eligibility through preference and attribution commit.
            // Compatibility constructors retain legacy targets only, never an unguarded announcement path.
            if (announcementAccess == null || announcementAnalytics == null) {
                throw new IllegalStateException("Announcement feedback dependencies are unavailable");
            }
            announcementAccess.requireVisible(viewerUserId, delivery.targetId());
        }
        if (request.action() == MusicianFeedFeedbackAction.REPORT) {
            reports.report(viewerUserId, delivery, normalizedReason, now);
        }
        Optional<MusicianFeedFeedback> existingFeedback = repository
                .findByViewerUserIdAndActionAndScopeKey(viewerUserId, request.action(), scope);
        MusicianFeedFeedback value = existingFeedback
                .map(existing -> {
                    existing.updateReason(normalizedReason, now);
                    return existing;
                })
                .orElseGet(() -> MusicianFeedFeedback.item(viewerUserId, request.action(), itemId,
                        itemType, delivery.deliveryId(), normalizedReason, now));
        MusicianFeedFeedback saved = repository.save(value);
        if (itemType == MusicianFeedItemType.ANNOUNCEMENT
                && request.action() == MusicianFeedFeedbackAction.HIDE && existingFeedback.isEmpty()) {
            announcementAnalytics.recordEngagement(viewerUserId, delivery.targetId(), saved.getId(),
                    AnnouncementAnalyticsStore.EngagementMetric.HIDE, now);
        }
        return response(saved);
    }

    @Transactional
    public MusicianFeedFeedbackResponse mute(UUID viewerUserId, String profileType, UUID profileId) {
        viewerGuard.requireMusicianProfile(viewerUserId);
        return muteAuthorized(viewerUserId, profileType, profileId);
    }

    @Transactional
    public MusicianFeedFeedbackResponse recordItemForListener(
            UUID viewerUserId, String itemId, MusicianFeedFeedbackRequest request) {
        viewerGuard.requireListenerProfile(viewerUserId);
        return recordItemAuthorized(viewerUserId, itemId, request, BackstageFeedAudience.LISTENER);
    }

    @Transactional
    public MusicianFeedFeedbackResponse muteForVenue(UUID viewerUserId, String profileType, UUID profileId) {
        viewerGuard.requireVenueProfile(viewerUserId);
        return muteAuthorized(viewerUserId, profileType, profileId);
    }

    @Transactional
    public MusicianFeedFeedbackResponse recordItemForStudio(
            UUID viewerUserId, String itemId, MusicianFeedFeedbackRequest request) {
        viewerGuard.requireStudioProfile(viewerUserId);
        return recordItemAuthorized(viewerUserId, itemId, request, BackstageFeedAudience.STUDIO);
    }

    @Transactional
    public MusicianFeedFeedbackResponse muteForStudio(UUID viewerUserId, String profileType, UUID profileId) {
        viewerGuard.requireStudioProfile(viewerUserId);
        return muteAuthorized(viewerUserId, profileType, profileId);
    }

    private MusicianFeedFeedbackResponse muteAuthorized(UUID viewerUserId, String profileType, UUID profileId) {
        String normalizedType = authorProfiles.requireEligibleNotOwned(viewerUserId, profileType, profileId);
        feedbackLock.viewer(viewerUserId);
        String scope = "AUTHOR:" + normalizedType + ":" + profileId;
        MusicianFeedFeedback value = repository
                .findByViewerUserIdAndActionAndScopeKey(viewerUserId,
                        MusicianFeedFeedbackAction.MUTE_AUTHOR, scope)
                .orElseGet(() -> MusicianFeedFeedback.mute(viewerUserId, normalizedType, profileId, now()));
        return response(repository.save(value));
    }

    @Transactional
    public void unmute(UUID viewerUserId, String profileType, UUID profileId) {
        viewerGuard.requireMusicianProfile(viewerUserId);
        unmuteAuthorized(viewerUserId, profileType, profileId);
    }

    @Transactional
    public MusicianFeedFeedbackResponse muteForListener(UUID viewerUserId, String profileType, UUID profileId) {
        viewerGuard.requireListenerProfile(viewerUserId);
        return muteAuthorized(viewerUserId, listenerAuthorType(profileType, profileId), profileId);
    }

    @Transactional
    public void unmuteForVenue(UUID viewerUserId, String profileType, UUID profileId) {
        viewerGuard.requireVenueProfile(viewerUserId);
        unmuteAuthorized(viewerUserId, profileType, profileId);
    }

    @Transactional
    public void unmuteForStudio(UUID viewerUserId, String profileType, UUID profileId) {
        viewerGuard.requireStudioProfile(viewerUserId);
        unmuteAuthorized(viewerUserId, profileType, profileId);
    }

    private void unmuteAuthorized(UUID viewerUserId, String profileType, UUID profileId) {
        String normalizedType = authorProfiles.normalize(profileType, profileId);
        feedbackLock.viewer(viewerUserId);
        repository.deleteByViewerUserIdAndActionAndScopeKey(viewerUserId,
                MusicianFeedFeedbackAction.MUTE_AUTHOR,
                "AUTHOR:" + normalizedType + ":" + profileId);
    }

    @Transactional
    public void unmuteForListener(UUID viewerUserId, String profileType, UUID profileId) {
        viewerGuard.requireListenerProfile(viewerUserId);
        unmuteAuthorized(viewerUserId, listenerAuthorType(profileType, profileId), profileId);
    }

    private String listenerAuthorType(String profileType, UUID profileId) {
        String type = authorProfiles.normalize(profileType, profileId);
        if (!Set.of("LISTENER", "MUSICIAN", "BAND", "VENUE").contains(type)) throw invalid();
        return type;
    }

    private MusicianFeedFeedbackResponse response(MusicianFeedFeedback value) {
        return new MusicianFeedFeedbackResponse(value.getId(), value.getAction(),
                value.getItemId(), value.getAuthorProfileType(), value.getAuthorProfileId(), value.getUpdatedAt());
    }

    private String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) return null;
        String value = reason.strip();
        if (value.codePointCount(0, value.length()) > 500 || value.codePoints().anyMatch(codePoint ->
                (Character.isISOControl(codePoint) && codePoint != '\n' && codePoint != '\t')
                        || (codePoint >= 0xd800 && codePoint <= 0xdfff))) throw invalid();
        return value;
    }

    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
    private SoundConnectException invalid() { return new SoundConnectException(ErrorType.BAD_REQUEST); }
}
