package com.berkayb.soundconnect.modules.feed.musician.mixer;

import com.berkayb.soundconnect.modules.feed.musician.announcement.MusicianFeedAnnouncementPlan;
import com.berkayb.soundconnect.modules.feed.musician.api.*;
import com.berkayb.soundconnect.modules.feed.musician.candidate.*;
import com.berkayb.soundconnect.modules.feed.musician.delivery.MusicianFeedDeliverySnapshot;
import com.berkayb.soundconnect.modules.feed.musician.feedback.MusicianFeedFeedbackSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

class MusicianFeedAnnouncementMixerTest {
    private static final UUID VIEWER = new UUID(1, 90);
    private static final Instant NOW = Instant.parse("2026-09-13T12:00:00Z");
    private final MusicianFeedMixer mixer = new MusicianFeedMixer();

    @Test void variablePagesIncludingOneKeepThreeDistinctAnnouncementsAndNeverTouchPromotions() {
        var plan = plan(1, 4, 7);
        List<MusicianFeedCandidate> organic = new ArrayList<>();
        for (int id = 0; id < 55; id++) organic.add(normal(id));
        plan.entries().forEach(entry -> organic.add(announcement(entry)));
        List<MusicianFeedCandidate> sponsors = new ArrayList<>();
        for (int id = 0; id < 20; id++) sponsors.add(sponsor(id));
        List<MusicianFeedItemResponse> delivered = new ArrayList<>();
        int[] sizes = {1, 2, 1, 5, 3};
        boolean hasMore = true;
        for (int page = 0; hasMore && page < 150; page++) {
            var result = page(organic, sponsors, plan, delivered, sizes[page % sizes.length], Set.of());
            assertThat(result.items()).hasSizeLessThanOrEqualTo(sizes[page % sizes.length]);
            delivered.addAll(result.items());
            hasMore = result.hasMore();
        }
        assertThat(hasMore).isFalse();
        var announcements = delivered.stream().filter(item -> item.type() == MusicianFeedItemType.ANNOUNCEMENT).toList();
        assertThat(announcements).hasSize(3);
        assertThat(announcements).extracting(item -> item.target().id()).doesNotHaveDuplicates();
        assertThat(delivered.indexOf(announcements.getFirst())).isBetween(1, 2);
        int lastAnnouncement = -1;
        for (int index = 0; index < delivered.size(); index++) {
            var current = delivered.get(index);
            if (index > 0 && special(current)) assertThat(special(delivered.get(index - 1))).isFalse();
            if (current.type() == MusicianFeedItemType.ANNOUNCEMENT) {
                if (lastAnnouncement >= 0) {
                    long normal = delivered.subList(lastAnnouncement + 1, index).stream().filter(item -> !special(item)).count();
                    assertThat(normal).isGreaterThanOrEqualTo(4);
                }
                lastAnnouncement = index;
            }
        }
        assertThat(snapshot(delivered).deliveredNormalCount()).isEqualTo(55);
        assertThat(delivered.stream().filter(item -> item.promotion() != null).count()).isGreaterThan(0);
    }

    @Test void thresholdAtPageEndKeepsTheNextSingleItemPageWithoutInventingOrganicContent() {
        var plan = plan(1, 4, 8);
        List<MusicianFeedCandidate> candidates = new ArrayList<>(List.of(normal(1)));
        plan.entries().forEach(entry -> candidates.add(announcement(entry)));
        List<MusicianFeedItemResponse> delivered = new ArrayList<>();
        var first = page(candidates, List.of(), plan, delivered, 1, Set.of());
        assertThat(first.items()).singleElement().satisfies(item -> assertThat(item.type()).isEqualTo(MusicianFeedItemType.TRACK));
        assertThat(first.hasMore()).isTrue();
        delivered.addAll(first.items());
        var second = page(candidates, List.of(), plan, delivered, 1, Set.of());
        assertThat(second.items()).singleElement().satisfies(item -> assertThat(item.type()).isEqualTo(MusicianFeedItemType.ANNOUNCEMENT));
        assertThat(second.deliveredOrganicCount()).isEqualTo(1);
        assertThat(second.hasMore()).isFalse();
        assertThat(page(List.of(announcement(plan.entries().getFirst())), List.of(), plan, List.of(), 20, Set.of()).items()).isEmpty();
    }

    @Test void hiddenOrWithdrawnPlannedIdsNeverCauseAReplacementOrAnImpossibleContinuation() {
        var plan = plan(2, 4, 7);
        var candidates = new ArrayList<MusicianFeedCandidate>();
        for (int id = 0; id < 20; id++) candidates.add(normal(id));
        candidates.add(announcement(plan.entries().get(0)));
        candidates.add(announcement(plan.entries().get(2))); // second was withdrawn from the source
        var unrelated = new MusicianFeedAnnouncementPlan.Entry(new UUID(99, 9), 1);
        candidates.add(announcement(unrelated));
        var hidden = Set.of("ANNOUNCEMENT:" + plan.entries().getFirst().id());
        var result = page(candidates, List.of(), plan, List.of(), 30, hidden);
        assertThat(result.items().stream().filter(item -> item.type() == MusicianFeedItemType.ANNOUNCEMENT))
                .extracting(item -> item.target().id()).containsExactly(plan.entries().get(2).id());
        assertThat(result.hasMore()).isFalse();
    }

    @Test void delayedAnnouncementRestartsItsGapAndDoesNotCountThePrecedingPromotion() {
        var plan = plan(1, 4, 4);
        List<MusicianFeedItemResponse> previous = new ArrayList<>();
        for (int id = 0; id < 10; id++) previous.add(normal(id).toResponse());
        previous.add(sponsor(0).toResponse());
        List<MusicianFeedCandidate> candidates = new ArrayList<>();
        for (int id = 10; id < 18; id++) candidates.add(normal(id));
        plan.entries().forEach(entry -> candidates.add(announcement(entry)));
        var result = page(candidates, List.of(), plan, previous, 20, Set.of());
        assertThat(result.items().getFirst().type()).isEqualTo(MusicianFeedItemType.TRACK);
        var indices = new ArrayList<Integer>();
        for (int index = 0; index < result.items().size(); index++) {
            if (result.items().get(index).type() == MusicianFeedItemType.ANNOUNCEMENT) indices.add(index);
        }
        assertThat(indices).containsExactly(1, 6);
        assertThat(result.deliveredOrganicCount()).isEqualTo(18);
    }

    private MusicianFeedMixer.MixedPage page(List<MusicianFeedCandidate> organic, List<MusicianFeedCandidate> sponsors,
                                             MusicianFeedAnnouncementPlan plan, List<MusicianFeedItemResponse> previous,
                                             int size, Set<String> hidden) {
        var state = snapshot(previous);
        var candidates = organic.stream().filter(item -> !state.itemIds().contains(item.itemId())).toList();
        var remainingSponsors = sponsors.stream().filter(item -> !state.itemIds().contains(item.itemId())).toList();
        return mixer.mix(VIEWER, NOW, size, Set.of(MusicianFeedItemType.TRACK, MusicianFeedItemType.ANNOUNCEMENT,
                        MusicianFeedItemType.SPONSORED), new MusicianFeedFeedbackSnapshot(hidden, Set.of(), Map.of(), "0"),
                candidates, remainingSponsors, null, state.deliveredNormalCount(), state.deliveredPromotionCount(),
                state.lastItemPromoted(), state.lastItemType(), state.lastItemLane(), state.organicCountAtLastPromotion(),
                0, 0, plan, state);
    }

    private static MusicianFeedDeliverySnapshot snapshot(List<MusicianFeedItemResponse> previous) {
        Set<String> ids = new HashSet<>(), targets = new HashSet<>(), promotedTargets = new HashSet<>();
        Set<UUID> campaigns = new HashSet<>(), announcements = new HashSet<>();
        long normal = 0, promotionCount = 0, normalAtPromotion = 0, normalAtAnnouncement = 0;
        for (var item : previous) {
            ids.add(item.id());
            targets.add(item.target().type() + ":" + item.target().id());
            if (item.type() == MusicianFeedItemType.ANNOUNCEMENT) {
                announcements.add(item.target().id());
                normalAtAnnouncement = normal;
            } else if (item.promotion() != null) {
                promotionCount++;
                normalAtPromotion = normal;
                campaigns.add(item.promotion().campaignId());
                promotedTargets.add(item.target().type() + ":" + item.target().id());
            } else normal++;
        }
        var last = previous.isEmpty() ? null : previous.getLast();
        return new MusicianFeedDeliverySnapshot(ids, targets, targets, promotedTargets, campaigns,
                previous.size(), promotionCount, normalAtPromotion, last != null && last.promotion() != null,
                last == null ? null : last.type(), last == null ? null : MusicianFeedLane.FOLLOWING,
                0, 0, announcements, normal, normalAtAnnouncement);
    }

    private static MusicianFeedAnnouncementPlan plan(int a, int b, int c) {
        return new MusicianFeedAnnouncementPlan(List.of(new MusicianFeedAnnouncementPlan.Entry(new UUID(77, 1), a),
                new MusicianFeedAnnouncementPlan.Entry(new UUID(77, 2), b),
                new MusicianFeedAnnouncementPlan.Entry(new UUID(77, 3), c)));
    }
    private static boolean special(MusicianFeedItemResponse item) {
        return item.promotion() != null || item.type() == MusicianFeedItemType.ANNOUNCEMENT;
    }
    private static MusicianFeedCandidate announcement(MusicianFeedAnnouncementPlan.Entry entry) {
        return new MusicianFeedCandidate("ANNOUNCEMENT:" + entry.id(), MusicianFeedItemType.ANNOUNCEMENT, 1, NOW,
                new MusicianFeedItemResponse.Reason(MusicianFeedReasonCode.PLATFORM_ANNOUNCEMENT, List.of(), 0), null,
                new MusicianFeedItemResponse.Target("ANNOUNCEMENT", entry.id()), null, null,
                List.of(MusicianFeedFeedbackAction.HIDE), Map.of("id", entry.id()), 0, 0, MusicianFeedLane.SYSTEM, false, entry);
    }
    private static MusicianFeedCandidate normal(int id) {
        var author = new MusicianFeedItemResponse.Author(new UUID(10, id), new UUID(11, id), "MUSICIAN", "member", "Member", null, true);
        return new MusicianFeedCandidate("TRACK:" + id, MusicianFeedItemType.TRACK, 1, NOW.minusSeconds(id),
                new MusicianFeedItemResponse.Reason(MusicianFeedReasonCode.FOLLOWING_PUBLICATION, List.of(), 0), author,
                new MusicianFeedItemResponse.Target("MEDIA", new UUID(12, id)), null, null, List.of(), Map.of(),
                1_000_000, 0, MusicianFeedLane.FOLLOWING, false);
    }
    private static MusicianFeedCandidate sponsor(int id) {
        return new MusicianFeedCandidate("SPONSORED:" + id, MusicianFeedItemType.SPONSORED, 1, NOW,
                new MusicianFeedItemResponse.Reason(MusicianFeedReasonCode.SPONSORED, List.of(), 0), null,
                new MusicianFeedItemResponse.Target("STANDALONE", new UUID(13, id)), null,
                new MusicianFeedItemResponse.Promotion(new UUID(14, id), "Sponsored", "Open", "/open"),
                List.of(), Map.of(), 0, 0, MusicianFeedLane.SYSTEM, false);
    }
}
