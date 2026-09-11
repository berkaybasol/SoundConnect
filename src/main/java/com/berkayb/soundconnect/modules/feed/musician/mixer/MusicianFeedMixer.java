package com.berkayb.soundconnect.modules.feed.musician.mixer;

import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedItemResponse;
import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedItemType;
import com.berkayb.soundconnect.modules.feed.musician.candidate.MusicianFeedCandidate;
import com.berkayb.soundconnect.modules.feed.musician.candidate.MusicianFeedLane;
import com.berkayb.soundconnect.modules.feed.musician.cursor.MusicianFeedCursorState;
import com.berkayb.soundconnect.modules.feed.musician.feedback.MusicianFeedFeedbackSnapshot;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Component
public class MusicianFeedMixer {
    private static final long MAX_FRESHNESS_BONUS = 120_000L;
    private static final long SHOW_LESS_PENALTY = 45_000L;
    private static final int ORGANIC_ITEMS_PER_PROMOTION = 8;

    public MixedPage mix(
            UUID viewerUserId,
            Instant anchor,
            int pageSize,
            Set<MusicianFeedItemType> supportedTypes,
            MusicianFeedFeedbackSnapshot feedback,
            Collection<MusicianFeedCandidate> organicCandidates,
            Collection<MusicianFeedCandidate> sponsorCandidates,
            MusicianFeedCursorState.CursorPosition after,
            long deliveredOrganicCount
    ) {
        return mix(viewerUserId, anchor, pageSize, supportedTypes, feedback, organicCandidates,
                sponsorCandidates, after, deliveredOrganicCount, 0, false);
    }

    public MixedPage mix(
            UUID viewerUserId,
            Instant anchor,
            int pageSize,
            Set<MusicianFeedItemType> supportedTypes,
            MusicianFeedFeedbackSnapshot feedback,
            Collection<MusicianFeedCandidate> organicCandidates,
            Collection<MusicianFeedCandidate> sponsorCandidates,
            MusicianFeedCursorState.CursorPosition after,
            long deliveredOrganicCount,
            long deliveredPromotionCount,
            boolean lastItemPromoted
    ) {
        return mix(viewerUserId, anchor, pageSize, supportedTypes, feedback, organicCandidates,
                sponsorCandidates, after, deliveredOrganicCount, deliveredPromotionCount,
                lastItemPromoted, null, deliveredPromotionCount * ORGANIC_ITEMS_PER_PROMOTION);
    }

    public MixedPage mix(
            UUID viewerUserId,
            Instant anchor,
            int pageSize,
            Set<MusicianFeedItemType> supportedTypes,
            MusicianFeedFeedbackSnapshot feedback,
            Collection<MusicianFeedCandidate> organicCandidates,
            Collection<MusicianFeedCandidate> sponsorCandidates,
            MusicianFeedCursorState.CursorPosition after,
            long deliveredOrganicCount,
            long deliveredPromotionCount,
            boolean lastItemPromoted,
            MusicianFeedItemType lastItemType
    ) {
        return mix(viewerUserId, anchor, pageSize, supportedTypes, feedback, organicCandidates,
                sponsorCandidates, after, deliveredOrganicCount, deliveredPromotionCount,
                lastItemPromoted, lastItemType,
                deliveredPromotionCount * ORGANIC_ITEMS_PER_PROMOTION);
    }

    public MixedPage mix(
            UUID viewerUserId,
            Instant anchor,
            int pageSize,
            Set<MusicianFeedItemType> supportedTypes,
            MusicianFeedFeedbackSnapshot feedback,
            Collection<MusicianFeedCandidate> organicCandidates,
            Collection<MusicianFeedCandidate> sponsorCandidates,
            MusicianFeedCursorState.CursorPosition after,
            long deliveredOrganicCount,
            long deliveredPromotionCount,
            boolean lastItemPromoted,
            MusicianFeedItemType lastItemType,
            long organicCountAtLastPromotion
    ) {
        return mix(viewerUserId, anchor, pageSize, supportedTypes, feedback, organicCandidates,
                sponsorCandidates, after, deliveredOrganicCount, deliveredPromotionCount,
                lastItemPromoted, lastItemType, legacyLane(lastItemType), organicCountAtLastPromotion);
    }

    public MixedPage mix(
            UUID viewerUserId,
            Instant anchor,
            int pageSize,
            Set<MusicianFeedItemType> supportedTypes,
            MusicianFeedFeedbackSnapshot feedback,
            Collection<MusicianFeedCandidate> organicCandidates,
            Collection<MusicianFeedCandidate> sponsorCandidates,
            MusicianFeedCursorState.CursorPosition after,
            long deliveredOrganicCount,
            long deliveredPromotionCount,
            boolean lastItemPromoted,
            MusicianFeedItemType lastItemType,
            MusicianFeedLane lastItemLane,
            long organicCountAtLastPromotion
    ) {
        List<ScoredCandidate> organic = scoreAndFilter(viewerUserId, anchor, supportedTypes, feedback,
                organicCandidates, false);
        organic.sort(SCORED_ORDER);
        // Continuation is session-ledger based. A single global boundary cannot
        // represent quota-selected lanes without skipping unconsumed higher lanes.

        List<ScoredCandidate> sponsors = scoreAndFilter(viewerUserId, anchor, supportedTypes, feedback,
                sponsorCandidates, true).stream().sorted(SCORED_ORDER).toList();

        List<ScoredCandidate> basePage = selectLaneWindow(organic, pageSize, lastItemLane);
        List<ScoredCandidate> diversePage = diversify(basePage);
        MergeResult merged = mergePromotions(diversePage, sponsors, pageSize, deliveredOrganicCount,
                deliveredPromotionCount, lastItemPromoted, organicCountAtLastPromotion);
        MusicianFeedCursorState.CursorPosition boundary = merged.lastDelivered() == null
                ? null : position(merged.lastDelivered());

        boolean organicHasMore = merged.deferredOrganic() > 0
                || organic.subList(Math.min(merged.organicExamined(), organic.size()), organic.size()).stream()
                .anyMatch(value -> isEligibleContinuation(value.candidate(), merged));
        boolean sponsorHasMore = promotionCanBeServedLater(sponsors, merged, organic);
        boolean hasMore = !merged.items().isEmpty() && (organicHasMore || sponsorHasMore);
        return new MixedPage(merged.items(), merged.itemLanes(), boundary, hasMore,
                deliveredOrganicCount + merged.organicEmitted());
    }

    private List<ScoredCandidate> scoreAndFilter(
            UUID viewer,
            Instant anchor,
            Set<MusicianFeedItemType> supported,
            MusicianFeedFeedbackSnapshot feedback,
            Collection<MusicianFeedCandidate> candidates,
            boolean promotion
    ) {
        Map<String, ScoredCandidate> distinct = new HashMap<>();
        for (MusicianFeedCandidate candidate : candidates) {
            if (candidate == null || !supported.contains(candidate.type())) continue;
            if (promotion != (candidate.promotion() != null)) continue;
            if (candidate.ownedByViewer()) continue;
            if (feedback.hiddenItemIds().contains(candidate.itemId())) continue;
            if (candidate.author() != null) {
                UUID authorId = candidate.author().userId();
                if (viewer.equals(authorId) || feedback.mutedAuthorKeys().contains(
                        MusicianFeedFeedbackSnapshot.authorKey(
                                candidate.author().profileType(), candidate.author().profileId()))) continue;
            }
            long score = score(candidate, anchor, feedback);
            ScoredCandidate next = new ScoredCandidate(candidate, score);
            distinct.merge(aggregationKey(candidate), next, this::mergeSocialProof);
        }
        return new ArrayList<>(distinct.values());
    }

    private ScoredCandidate mergeSocialProof(ScoredCandidate left, ScoredCandidate right) {
        ScoredCandidate stronger = SCORED_ORDER.compare(left, right) <= 0 ? left : right;
        ScoredCandidate presentation = nativePresentation(left, right);
        MusicianFeedCandidate base = presentation.candidate();
        MusicianFeedItemResponse.Reason reason = mergeReasons(left.candidate().reason(), right.candidate().reason());
        MusicianFeedCandidate merged = new MusicianFeedCandidate(base.itemId(), base.type(),
                base.payloadVersion(), base.occurredAt(), reason, base.author(), base.target(),
                base.engagement(), base.promotion(), base.feedbackCapabilities(), base.payload(),
                stronger.candidate().baseScore(), stronger.candidate().relevanceScore(),
                stronger.candidate().lane(), base.ownedByViewer());
        return new ScoredCandidate(merged, Math.max(left.score(), right.score()));
    }

    private ScoredCandidate nativePresentation(ScoredCandidate left, ScoredCandidate right) {
        boolean leftNative = !isActivity(left.candidate().type());
        boolean rightNative = !isActivity(right.candidate().type());
        if (leftNative != rightNative) return leftNative ? left : right;
        return SCORED_ORDER.compare(left, right) <= 0 ? left : right;
    }

    private MusicianFeedItemResponse.Reason mergeReasons(MusicianFeedItemResponse.Reason left,
                                                          MusicianFeedItemResponse.Reason right) {
        if (left == null) return right;
        if (right == null) return left;
        MusicianFeedItemResponse.Reason primary = reasonPriority(left.code()) >= reasonPriority(right.code())
                ? left : right;
        LinkedHashMap<String, MusicianFeedItemResponse.Author> actors = new LinkedHashMap<>();
        for (MusicianFeedItemResponse.Reason value : List.of(primary, primary == left ? right : left)) {
            for (MusicianFeedItemResponse.Author actor : value.actors()) {
                String key = actor.profileType() + ":" + actor.profileId() + ":" + actor.userId();
                actors.putIfAbsent(key, actor);
            }
        }
        int visible = Math.min(3, actors.size());
        int hidden = Math.max(0, actors.size() - visible)
                + left.secondaryActorCount() + right.secondaryActorCount();
        return new MusicianFeedItemResponse.Reason(primary.code(),
                actors.values().stream().limit(visible).toList(), hidden);
    }

    private int reasonPriority(com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedReasonCode code) {
        if (code == null) return 0;
        return switch (code) {
            case FOLLOWED_USER_COMMENTED -> 80;
            case FOLLOWED_USER_LIKED -> 70;
            case FOLLOWED_USER_FOLLOWED -> 60;
            case FOLLOWING_PUBLICATION -> 50;
            case CITY_AND_INSTRUMENT_MATCH -> 40;
            case CITY_MATCH, INSTRUMENT_MATCH -> 30;
            case FEATURED, PLATFORM_ANNOUNCEMENT -> 20;
            case DISCOVERY, PROFILE_INCOMPLETE, SPONSORED -> 10;
        };
    }

    private boolean isActivity(MusicianFeedItemType type) {
        return type == MusicianFeedItemType.ACTIVITY_COMMENT
                || type == MusicianFeedItemType.ACTIVITY_LIKE
                || type == MusicianFeedItemType.ACTIVITY_FOLLOW;
    }

    private long score(MusicianFeedCandidate candidate, Instant anchor, MusicianFeedFeedbackSnapshot feedback) {
        long ageHours = Math.max(0, Duration.between(candidate.occurredAt(), anchor).toHours());
        long freshness = Math.max(0, MAX_FRESHNESS_BONUS - Math.min(ageHours, 720) * 166L);
        long showLess = Math.min(feedback.showLessCounts().getOrDefault(candidate.type(), 0), 10)
                * SHOW_LESS_PENALTY;
        long discoveryPenalty = candidate.lane() == MusicianFeedLane.GENERAL_DISCOVERY ? 50_000L : 0L;
        long stableTieBreaker = Math.floorMod(candidate.itemId().hashCode(), 997);
        return candidate.baseScore() + candidate.relevanceScore() + freshness
                - showLess - discoveryPenalty + stableTieBreaker;
    }

    /**
     * General exploration has an explicit bounded budget. Relevant Collab/Event/
     * place opportunities remain in the primary stream and are not throttled as
     * random discovery. A sparse primary pool intentionally does not let general
     * discovery take over the entire page.
     */
    private List<ScoredCandidate> selectLaneWindow(List<ScoredCandidate> sorted, int slots,
                                                    MusicianFeedLane lastItemLane) {
        if (slots <= 0 || sorted.isEmpty()) return List.of();
        List<ScoredCandidate> system = sorted.stream()
                .filter(value -> value.candidate().lane() == MusicianFeedLane.SYSTEM)
                .limit(1).toList();
        List<ScoredCandidate> primary = sorted.stream()
                .filter(value -> value.candidate().lane() == MusicianFeedLane.FOLLOWING
                        || value.candidate().lane() == MusicianFeedLane.RELEVANT_OPPORTUNITY)
                .toList();
        List<ScoredCandidate> discovery = sorted.stream()
                .filter(value -> value.candidate().lane() == MusicianFeedLane.GENERAL_DISCOVERY)
                .toList();
        List<ScoredCandidate> moduleShares = sorted.stream()
                .filter(value -> value.candidate().lane() == MusicianFeedLane.MODULE_SHARE)
                .toList();

        int systemCount = Math.min(system.size(), slots);
        int remaining = slots - systemCount;
        int discoveryCount = discovery.isEmpty() || remaining == 0 ? 0
                : Math.min(discovery.size(), Math.max(1, (int) Math.ceil(remaining / 10.0d)));
        boolean previousWasModuleShare = lastItemLane == MusicianFeedLane.MODULE_SHARE;
        int moduleCount = moduleShares.isEmpty() || remaining == 0 || previousWasModuleShare ? 0
                : Math.min(moduleShares.size(), Math.max(1, (int) Math.ceil(remaining / 10.0d)));
        int primaryCount = Math.min(primary.size(), Math.max(0, remaining - discoveryCount - moduleCount));

        // A module share needs a non-module separator on either side. When the
        // feed is otherwise sparse, return fewer items rather than a module wall.
        int maxSeparatedModules = systemCount + discoveryCount + primaryCount + 1;
        moduleCount = Math.min(moduleCount, maxSeparatedModules);

        // Unused low-frequency reservations are work-conserving for the two
        // product-primary lanes, never for random discovery or module shares.
        int unfilled = remaining - primaryCount - discoveryCount - moduleCount;
        if (unfilled > 0) primaryCount += Math.min(unfilled, primary.size() - primaryCount);

        List<ScoredCandidate> selected = new ArrayList<>(systemCount + primaryCount + discoveryCount + moduleCount);
        selected.addAll(system.subList(0, systemCount));
        selected.addAll(primary.subList(0, primaryCount));
        selected.addAll(discovery.subList(0, discoveryCount));
        selected.addAll(moduleShares.subList(0, moduleCount));
        selected.sort(SCORED_ORDER);
        return selected;
    }

    /** Reorders exactly the selected keyset window; no candidate is dropped or moved across a cursor boundary. */
    private List<ScoredCandidate> diversify(List<ScoredCandidate> selected) {
        if (selected.size() < 2) return List.copyOf(selected);
        List<ScoredCandidate> remaining = new ArrayList<>(selected);
        List<ScoredCandidate> output = new ArrayList<>(selected.size());
        while (!remaining.isEmpty()) {
            ScoredCandidate best = remaining.stream().min((left, right) -> {
                long leftAdjusted = diversityAdjusted(left, output);
                long rightAdjusted = diversityAdjusted(right, output);
                int score = Long.compare(rightAdjusted, leftAdjusted);
                return score != 0 ? score : SCORED_ORDER.compare(left, right);
            }).orElseThrow();
            output.add(best);
            remaining.remove(best);
        }
        return List.copyOf(output);
    }

    private long diversityAdjusted(ScoredCandidate candidate, List<ScoredCandidate> selected) {
        long adjusted = candidate.score();
        String author = authorKey(candidate.candidate());
        int from = Math.max(0, selected.size() - 5);
        int authorCount = 0;
        int typeCount = 0;
        for (int index = from; index < selected.size(); index++) {
            MusicianFeedCandidate previous = selected.get(index).candidate();
            if (author != null && author.equals(authorKey(previous))) authorCount++;
            if (previous.type() == candidate.candidate().type()) typeCount++;
        }
        adjusted -= authorCount * 90_000L;
        adjusted -= typeCount * 45_000L;
        if (!selected.isEmpty()) {
            MusicianFeedCandidate previous = selected.getLast().candidate();
            if (author != null && author.equals(authorKey(previous))) {
                adjusted -= 160_000L;
            }
            if (previous.type() == candidate.candidate().type()) adjusted -= 70_000L;
            if (previous.lane() == MusicianFeedLane.MODULE_SHARE
                    && candidate.candidate().lane() == MusicianFeedLane.MODULE_SHARE) {
                adjusted -= 1_000_000_000L;
            }
        }
        return adjusted;
    }

    private MergeResult mergePromotions(
            List<ScoredCandidate> organic,
            List<ScoredCandidate> sponsors,
            int pageSize,
            long deliveredOrganic,
            long deliveredPromotions,
            boolean previousWasPromotion,
            long organicCountAtLastPromotion
    ) {
        List<MusicianFeedItemResponse> output = new ArrayList<>(pageSize);
        List<MusicianFeedLane> outputLanes = new ArrayList<>(pageSize);
        int organicIndex = 0;
        int sponsorIndex = 0;
        int organicEmitted = 0;
        long organicSeen = deliveredOrganic;
        long nextPromotionAt = deliveredPromotions == 0 ? ORGANIC_ITEMS_PER_PROMOTION
                : organicCountAtLastPromotion + ORGANIC_ITEMS_PER_PROMOTION;
        boolean lastWasPromotion = previousWasPromotion;
        Set<String> deliveredTargets = new HashSet<>();
        Set<String> promotedTargets = new HashSet<>();
        Set<String> emittedAggregationKeys = new HashSet<>();
        Set<String> sponsorTargets = new HashSet<>();
        sponsors.forEach(value -> sponsorTargets.add(targetKey(value.candidate())));
        Deque<ScoredCandidate> deferredNativeTargets = new ArrayDeque<>();
        ScoredCandidate boundaryCandidate = null;
        while (output.size() < pageSize && (organicIndex < organic.size()
                || !deferredNativeTargets.isEmpty() || sponsorIndex < sponsors.size())) {
            boolean mayPromote = sponsorIndex < sponsors.size()
                    && organicSeen >= nextPromotionAt
                    && organicSeen >= 2
                    && !lastWasPromotion;
            if (mayPromote) {
                ScoredCandidate sponsor = null;
                while (sponsorIndex < sponsors.size() && sponsor == null) {
                    ScoredCandidate candidate = sponsors.get(sponsorIndex++);
                    if (!deliveredTargets.contains(targetKey(candidate.candidate()))) sponsor = candidate;
                }
                if (sponsor != null) {
                    output.add(sponsor.candidate().toResponse());
                    outputLanes.add(sponsor.candidate().lane());
                    deliveredTargets.add(targetKey(sponsor.candidate()));
                    promotedTargets.add(targetKey(sponsor.candidate()));
                    boundaryCandidate = weaker(boundaryCandidate, sponsor);
                    lastWasPromotion = true;
                    deliveredPromotions++;
                    nextPromotionAt = organicSeen + ORGANIC_ITEMS_PER_PROMOTION;
                    continue;
                }
            }
            if (organicIndex < organic.size()) {
                ScoredCandidate candidate = organic.get(organicIndex++);
                if (promotedTargets.contains(targetKey(candidate.candidate()))
                        || !emittedAggregationKeys.add(aggregationKey(candidate.candidate()))) continue;
                if (!isActivity(candidate.candidate().type())
                        && sponsorTargets.contains(targetKey(candidate.candidate()))
                        && organicSeen < nextPromotionAt && output.size() + 1 < pageSize) {
                    emittedAggregationKeys.remove(aggregationKey(candidate.candidate()));
                    deferredNativeTargets.addLast(candidate);
                    continue;
                }
                output.add(candidate.candidate().toResponse());
                outputLanes.add(candidate.candidate().lane());
                deliveredTargets.add(targetKey(candidate.candidate()));
                organicSeen++;
                organicEmitted++;
                boundaryCandidate = weaker(boundaryCandidate, candidate);
                lastWasPromotion = false;
            } else if (!deferredNativeTargets.isEmpty()) {
                ScoredCandidate candidate = deferredNativeTargets.removeFirst();
                if (promotedTargets.contains(targetKey(candidate.candidate()))
                        || !emittedAggregationKeys.add(aggregationKey(candidate.candidate()))) continue;
                output.add(candidate.candidate().toResponse());
                outputLanes.add(candidate.candidate().lane());
                deliveredTargets.add(targetKey(candidate.candidate()));
                organicSeen++;
                organicEmitted++;
                boundaryCandidate = weaker(boundaryCandidate, candidate);
                lastWasPromotion = false;
            } else {
                break;
            }
        }
        deferredNativeTargets.removeIf(value -> promotedTargets.contains(targetKey(value.candidate())));
        return new MergeResult(List.copyOf(output), List.copyOf(outputLanes), organicIndex,
                deferredNativeTargets.size(), sponsorIndex, organicEmitted, organicSeen,
                nextPromotionAt, lastWasPromotion, Set.copyOf(deliveredTargets),
                Set.copyOf(promotedTargets), boundaryCandidate);
    }

    private boolean isEligibleContinuation(MusicianFeedCandidate candidate, MergeResult merged) {
        String target = targetKey(candidate);
        if (merged.promotedTargets().contains(target)) return false;
        return candidate.type() == MusicianFeedItemType.ACTIVITY_COMMENT
                || !merged.deliveredTargets().contains(target);
    }

    private ScoredCandidate weaker(ScoredCandidate current, ScoredCandidate candidate) {
        if (current == null) return candidate;
        return SCORED_ORDER.compare(current, candidate) < 0 ? candidate : current;
    }

    private boolean promotionCanBeServedLater(List<ScoredCandidate> sponsors, MergeResult merged,
                                               List<ScoredCandidate> organic) {
        boolean sponsorRemains = sponsors.subList(Math.min(merged.sponsorExamined(), sponsors.size()), sponsors.size())
                .stream().anyMatch(value -> !merged.deliveredTargets().contains(targetKey(value.candidate())));
        if (!sponsorRemains) return false;
        long requiredOrganic = Math.max(0L, merged.nextPromotionAt() - merged.organicSeen());
        requiredOrganic = Math.max(requiredOrganic, Math.max(0L, 2L - merged.organicSeen()));
        if (merged.lastWasPromotion()) requiredOrganic = Math.max(requiredOrganic, 1L);
        long remainingOrganic = merged.deferredOrganic()
                + organic.subList(Math.min(merged.organicExamined(), organic.size()), organic.size()).stream()
                .filter(value -> isEligibleContinuation(value.candidate(), merged)).count();
        return remainingOrganic >= requiredOrganic;
    }

    private boolean isAfter(ScoredCandidate value, MusicianFeedCursorState.CursorPosition after) {
        if (value.score() != after.rankKey()) return value.score() < after.rankKey();
        int occurred = value.candidate().occurredAt().compareTo(after.occurredAt());
        if (occurred != 0) return occurred < 0;
        return value.candidate().itemId().compareTo(after.itemId()) > 0;
    }

    private MusicianFeedCursorState.CursorPosition position(ScoredCandidate value) {
        return new MusicianFeedCursorState.CursorPosition(
                value.score(), value.candidate().occurredAt(), value.candidate().itemId());
    }

    private static MusicianFeedLane legacyLane(MusicianFeedItemType itemType) {
        if (itemType == MusicianFeedItemType.OVERTHINKING_PROFILE_SHARE
                || itemType == MusicianFeedItemType.TABLEGROUP_PROFILE_SHARE) {
            return MusicianFeedLane.MODULE_SHARE;
        }
        return null;
    }

    private static String targetKey(MusicianFeedCandidate candidate) {
        return candidate.target().type() + ":" + candidate.target().id();
    }

    private static String authorKey(MusicianFeedCandidate candidate) {
        if (candidate.author() == null || candidate.author().profileType() == null
                || candidate.author().profileId() == null) return null;
        return candidate.author().profileType() + ":" + candidate.author().profileId();
    }

    private static String aggregationKey(MusicianFeedCandidate candidate) {
        // Comments are individual social stories; likes/follows and native publications
        // may collapse onto the same native target.
        if (candidate.type() == MusicianFeedItemType.ACTIVITY_COMMENT) return candidate.itemId();
        return targetKey(candidate);
    }

    private static final Comparator<ScoredCandidate> SCORED_ORDER = Comparator
            .comparingLong(ScoredCandidate::score).reversed()
            .thenComparing(value -> value.candidate().occurredAt(), Comparator.reverseOrder())
            .thenComparing(value -> value.candidate().itemId());

    private record ScoredCandidate(MusicianFeedCandidate candidate, long score) { }
    private record MergeResult(List<MusicianFeedItemResponse> items, List<MusicianFeedLane> itemLanes,
                               int organicExamined, int deferredOrganic, int sponsorExamined,
                               int organicEmitted, long organicSeen,
                               long nextPromotionAt, boolean lastWasPromotion,
                               Set<String> deliveredTargets, Set<String> promotedTargets,
                               ScoredCandidate lastDelivered) { }

    public record MixedPage(
            List<MusicianFeedItemResponse> items,
            List<MusicianFeedLane> itemLanes,
            MusicianFeedCursorState.CursorPosition cursorBoundary,
            boolean hasMore,
            long deliveredOrganicCount
    ) {
        public MixedPage {
            items = List.copyOf(items);
            itemLanes = List.copyOf(itemLanes);
            if (items.size() != itemLanes.size()) {
                throw new IllegalArgumentException("Each feed item must retain its source lane");
            }
        }
    }
}
