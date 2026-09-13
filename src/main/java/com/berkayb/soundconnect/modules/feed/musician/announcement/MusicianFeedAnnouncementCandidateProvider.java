package com.berkayb.soundconnect.modules.feed.musician.announcement;

import com.berkayb.soundconnect.modules.feed.musician.api.*;
import com.berkayb.soundconnect.modules.feed.musician.candidate.*;
import com.berkayb.soundconnect.modules.feed.musician.feedback.MusicianFeedFeedbackReader;
import com.berkayb.soundconnect.modules.promotion.announcement.AnnouncementReadService;
import com.berkayb.soundconnect.modules.promotion.announcement.AnnouncementResponse;
import org.springframework.stereotype.Component;

import java.util.*;

/** Current eligibility is resolved by Promotion; ranking and persistent feed-only hiding remain feed concerns. */
@Component
public class MusicianFeedAnnouncementCandidateProvider implements MusicianFeedCandidateProvider {
    static final int BATCH_SIZE = 160;
    private final AnnouncementReadService announcements;
    private final AnnouncementImpressionHistory impressions;
    private final MusicianFeedFeedbackReader feedback;

    public MusicianFeedAnnouncementCandidateProvider(AnnouncementReadService announcements,
                                                      AnnouncementImpressionHistory impressions,
                                                      MusicianFeedFeedbackReader feedback) {
        this.announcements = announcements;
        this.impressions = impressions;
        this.feedback = feedback;
    }

    @Override public String providerId() { return "platform-announcements"; }
    @Override public Set<MusicianFeedItemType> supportedTypes() { return Set.of(MusicianFeedItemType.ANNOUNCEMENT); }

    @Override public List<MusicianFeedCandidate> findCandidates(MusicianFeedCandidateRequest request) {
        if (request.announcementPlan() != null) return resolvePlan(request);
        var selector = new MusicianFeedAnnouncementSelector(request.viewerUserId(), request.feedSessionId());
        String cursor = null;
        do {
            requireTime(request);
            var page = announcements.findForFeedBatch(request.viewerUserId(), "MUSICIAN", request.anchor(), cursor, BATCH_SIZE);
            if (page == null || page.items().size() > BATCH_SIZE) throw new IllegalStateException("Invalid announcement batch");
            List<MusicianFeedCandidate> probes = page.items().stream()
                    .map(value -> candidate(value, new MusicianFeedAnnouncementPlan.Entry(value.id(), 4))).toList();
            var preferences = feedback.forCandidates(request.viewerUserId(), request.feedback(), probes, List.of());
            List<UUID> ids = page.items().stream().map(AnnouncementResponse::id).toList();
            Map<UUID, Long> counts = ids.isEmpty() ? Map.of()
                    : impressions.qualifiedImpressionCounts(request.viewerUserId(), ids, request.anchor());
            requireTime(request);
            for (AnnouncementResponse value : page.items()) {
                if (!preferences.hiddenItemIds().contains(itemId(value.id()))) {
                    selector.consider(value, counts.getOrDefault(value.id(), 0L));
                }
            }
            if (!page.hasMore()) break;
            if (page.items().isEmpty() || page.nextCursor() == null || page.nextCursor().equals(cursor)) {
                throw new IllegalStateException("Announcement candidate cursor did not advance");
            }
            cursor = page.nextCursor();
        } while (true);
        return selector.selected().stream().limit(request.limit())
                .map(value -> candidate(value.value(), value.placement())).toList();
    }

    private List<MusicianFeedCandidate> resolvePlan(MusicianFeedCandidateRequest request) {
        List<MusicianFeedAnnouncementPlan.Entry> remaining = request.announcementPlan().entries().stream()
                .filter(entry -> !request.delivery().itemIds().contains(itemId(entry.id()))).toList();
        if (remaining.isEmpty()) return List.of();
        requireTime(request);
        var values = announcements.findForFeedByIds(request.viewerUserId(), "MUSICIAN",
                remaining.stream().map(MusicianFeedAnnouncementPlan.Entry::id).toList(), request.readAt());
        Map<UUID, AnnouncementResponse> byId = new HashMap<>();
        for (AnnouncementResponse value : values) {
            if (!value.firstPublishedAt().isAfter(request.anchor())) byId.put(value.id(), value);
        }
        requireTime(request);
        return remaining.stream().filter(entry -> byId.containsKey(entry.id())).limit(request.limit())
                .map(entry -> candidate(byId.get(entry.id()), entry)).toList();
    }

    private static MusicianFeedCandidate candidate(AnnouncementResponse value, MusicianFeedAnnouncementPlan.Entry entry) {
        var engagement = value.engagement();
        return new MusicianFeedCandidate(itemId(value.id()), MusicianFeedItemType.ANNOUNCEMENT, 1,
                value.firstPublishedAt(), new MusicianFeedItemResponse.Reason(
                MusicianFeedReasonCode.PLATFORM_ANNOUNCEMENT, List.of(), 0), null,
                new MusicianFeedItemResponse.Target("ANNOUNCEMENT", value.id()),
                new MusicianFeedItemResponse.Engagement("ANNOUNCEMENT", value.id(), engagement.likeCount(),
                        engagement.commentCount(), engagement.likedByMe(), true, true), null,
                List.of(MusicianFeedFeedbackAction.HIDE), value, 0, 0, MusicianFeedLane.SYSTEM, false, entry);
    }

    private static String itemId(UUID id) { return "ANNOUNCEMENT:" + id; }
    private static void requireTime(MusicianFeedCandidateRequest request) {
        if (Thread.currentThread().isInterrupted() || (request.providerDeadlineNanos() != Long.MAX_VALUE
                && request.providerDeadlineNanos() - System.nanoTime() <= 0)) {
            throw new IllegalStateException("Announcement selection exceeded the shared feed deadline");
        }
    }
}
