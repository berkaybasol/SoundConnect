package com.berkayb.soundconnect.modules.feed.musician.mixer;

import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedItemResponse;
import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedItemType;
import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedPayloads;
import com.berkayb.soundconnect.modules.collab.enums.CollabWantedType;
import com.berkayb.soundconnect.modules.feed.musician.core.BackstageFeedAudience;
import com.berkayb.soundconnect.modules.feed.musician.announcement.MusicianFeedAnnouncementPlan;
import com.berkayb.soundconnect.modules.feed.musician.delivery.MusicianFeedDeliverySnapshot;
import com.berkayb.soundconnect.modules.feed.musician.candidate.MusicianFeedCandidate;
import com.berkayb.soundconnect.modules.feed.musician.candidate.MusicianFeedLane;
import com.berkayb.soundconnect.modules.feed.musician.cursor.MusicianFeedCursorState;
import com.berkayb.soundconnect.modules.feed.musician.feedback.MusicianFeedFeedbackSnapshot;
import com.berkayb.soundconnect.modules.feed.musician.sponsor.MusicianFeedPromotionCadence;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Component
public class MusicianFeedMixer {
    private static final long MAX_FRESHNESS_BONUS = 120_000L;
    private static final long SHOW_LESS_PENALTY = 45_000L;
    private static final long RECENTLY_VIEWED_PENALTY = 240_000L;

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
                lastItemPromoted, null, conservativeLastPromotionPosition(
                        deliveredOrganicCount, deliveredPromotionCount, lastItemPromoted));
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
                conservativeLastPromotionPosition(deliveredOrganicCount, deliveredPromotionCount, lastItemPromoted));
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
        return mix(viewerUserId, anchor, pageSize, supportedTypes, feedback, organicCandidates,
                sponsorCandidates, after, deliveredOrganicCount, deliveredPromotionCount,
                lastItemPromoted, lastItemType, lastItemLane, organicCountAtLastPromotion,
                0, 0);
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
            long organicCountAtLastPromotion,
            long deliveredOverthinkingShareCount,
            long deliveredTableGroupShareCount
    ) {
        return mix(viewerUserId, anchor, pageSize, supportedTypes, feedback, organicCandidates,
                sponsorCandidates, after, deliveredOrganicCount, deliveredPromotionCount, lastItemPromoted,
                lastItemType, lastItemLane, organicCountAtLastPromotion, deliveredOverthinkingShareCount,
                deliveredTableGroupShareCount, MusicianFeedAnnouncementPlan.EMPTY, null);
    }

    public MixedPage mix(UUID viewerUserId, Instant anchor, int pageSize,
                         Set<MusicianFeedItemType> supportedTypes, MusicianFeedFeedbackSnapshot feedback,
                         Collection<MusicianFeedCandidate> organicCandidates, Collection<MusicianFeedCandidate> sponsorCandidates,
                         MusicianFeedCursorState.CursorPosition after, long deliveredOrganicCount,
                         long deliveredPromotionCount, boolean lastItemPromoted, MusicianFeedItemType lastItemType,
                         MusicianFeedLane lastItemLane, long organicCountAtLastPromotion,
                         long deliveredOverthinkingShareCount, long deliveredTableGroupShareCount,
                         MusicianFeedAnnouncementPlan announcementPlan, MusicianFeedDeliverySnapshot deliveryState) {
        return mix(viewerUserId, anchor, pageSize, supportedTypes, feedback, organicCandidates, sponsorCandidates,
                after, deliveredOrganicCount, deliveredPromotionCount, lastItemPromoted, lastItemType,
                lastItemLane, organicCountAtLastPromotion, deliveredOverthinkingShareCount,
                deliveredTableGroupShareCount, announcementPlan, deliveryState, BackstageFeedAudience.MUSICIAN);
    }

    public MixedPage mix(UUID viewerUserId, Instant anchor, int pageSize,
                         Set<MusicianFeedItemType> supportedTypes, MusicianFeedFeedbackSnapshot feedback,
                         Collection<MusicianFeedCandidate> organicCandidates, Collection<MusicianFeedCandidate> sponsorCandidates,
                         MusicianFeedCursorState.CursorPosition after, long deliveredOrganicCount,
                         long deliveredPromotionCount, boolean lastItemPromoted, MusicianFeedItemType lastItemType,
                         MusicianFeedLane lastItemLane, long organicCountAtLastPromotion,
                         long deliveredOverthinkingShareCount, long deliveredTableGroupShareCount,
                         MusicianFeedAnnouncementPlan announcementPlan, MusicianFeedDeliverySnapshot deliveryState,
                         BackstageFeedAudience audience) {
        Objects.requireNonNull(audience, "audience");
        Map<UUID, ScoredCandidate> announcements = new LinkedHashMap<>();
        for (ScoredCandidate value : scoreAndFilter(viewerUserId, anchor, supportedTypes, feedback,
                organicCandidates.stream().filter(candidate -> candidate != null && candidate.type() == MusicianFeedItemType.ANNOUNCEMENT).toList(), false)) {
            announcements.put(value.candidate().target().id(), value);
        }
        List<ScoredCandidate> organic = scoreAndFilter(viewerUserId, anchor, supportedTypes, feedback,
                organicCandidates.stream().filter(candidate -> candidate != null && candidate.type() != MusicianFeedItemType.ANNOUNCEMENT)
                        .filter(candidate -> audience == BackstageFeedAudience.MUSICIAN
                                || candidate.type() != MusicianFeedItemType.PROFILE_COMPLETION).toList(), false);
        Set<String> recentlyViewed = deliveryState == null ? Set.of() : deliveryState.recentlyViewedTargetKeys();
        organic.replaceAll(value -> recentlyViewed.contains(targetKey(value.candidate()))
                && value.candidate().lane() != MusicianFeedLane.SYSTEM
                ? new ScoredCandidate(value.candidate(), value.score() - RECENTLY_VIEWED_PENALTY) : value);
        organic.sort(SCORED_ORDER);
        // Continuation is session-ledger based. A single global boundary cannot
        // represent quota-selected lanes without skipping unconsumed higher lanes.

        List<ScoredCandidate> sponsors = audience == BackstageFeedAudience.LISTENER ? List.of()
                : scoreAndFilter(viewerUserId, anchor, supportedTypes, feedback,
                    sponsorCandidates, true).stream().sorted(SCORED_ORDER).toList();

        List<ScoredCandidate> basePage = selectLaneWindow(organic, pageSize, lastItemLane,
                deliveredOverthinkingShareCount, deliveredTableGroupShareCount,
                deliveryState == null ? List.of() : deliveryState.recentOrganicHistory(), audience);
        MergeResult merged = mergePromotions(viewerUserId, anchor, basePage, organic, sponsors, pageSize, deliveredOrganicCount,
                deliveredPromotionCount, lastItemPromoted, organicCountAtLastPromotion,
                announcements, announcementPlan, deliveryState, lastItemType, audience);
        MusicianFeedCursorState.CursorPosition boundary = merged.lastDelivered() == null
                ? null : position(merged.lastDelivered());

        // Lane quotas and diversity can select a non-prefix of the ranked pool.
        // Only delivered identities prove consumption; a selected-list index cannot.
        boolean organicHasMore = organic.stream()
                .anyMatch(value -> isEligibleContinuation(value.candidate(), merged));
        boolean sponsorHasMore = promotionCanBeServedLater(sponsors, merged, organic);
        boolean announcementHasMore = announcementCanBeServedLater(merged, organic);
        boolean hasMore = !merged.items().isEmpty() && (organicHasMore || sponsorHasMore || announcementHasMore);
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
     * Selects from the entire eligible pool using diversity at each slot. A dense
     * primary stream gives discovery about 10%; a sparse stream backfills with
     * discovery. The audience receives a 20% minimum for matched jobs/events
     * (musician) or artists/performances (venue). Studio reserves 25% for explicit
     * studio requests and unfollowed artists, protecting both sources when they
     * are available. These are supply-aware budgets, not fixed placements.
     */
    private List<ScoredCandidate> selectLaneWindow(List<ScoredCandidate> sorted, int slots,
                                                    MusicianFeedLane lastItemLane,
                                                    long deliveredOverthinkingShareCount,
                                                    long deliveredTableGroupShareCount,
                                                    List<MusicianFeedDeliverySnapshot.OrganicHistoryEntry> history,
                                                    BackstageFeedAudience audience) {
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
        int discoveryCount = Math.min(discovery.size(), remaining / 10);
        boolean previousWasModuleShare = lastItemLane == MusicianFeedLane.MODULE_SHARE;
        int moduleCount = moduleShares.isEmpty() || remaining == 0 || previousWasModuleShare ? 0
                : Math.min(moduleShares.size(), Math.max(1, (int) Math.ceil(remaining / 10.0d)));
        // Low-frequency reservations must not consume the only primary slot.
        moduleCount = Math.min(moduleCount, Math.max(0, remaining - discoveryCount - Math.min(1, primary.size())));
        int primaryCount = Math.min(primary.size(), Math.max(0, remaining - discoveryCount - moduleCount));

        discoveryCount += Math.min(discovery.size() - discoveryCount,
                remaining - primaryCount - discoveryCount - moduleCount);

        // A module share needs a non-module separator on either side. When the
        // feed is otherwise sparse, return fewer items rather than a module wall.
        int maxSeparatedModules = systemCount + discoveryCount + primaryCount + 1;
        moduleCount = Math.min(moduleCount, maxSeparatedModules);

        Set<ScoredCandidate> chosenModules = new HashSet<>(selectModuleShares(moduleShares, moduleCount,
                deliveredOverthinkingShareCount, deliveredTableGroupShareCount));
        int opportunityMinimum = Math.min(primaryCount,
                Math.min((int) primary.stream().filter(value -> isMatchedOpportunity(value.candidate(), audience)).count(),
                        opportunityBudget(remaining, audience)));
        int studioRequestMinimum = audience == BackstageFeedAudience.STUDIO && opportunityMinimum > 0
                && primary.stream().anyMatch(value -> isStudioRequest(value.candidate())) ? 1 : 0;
        int studioArtistMinimum = audience == BackstageFeedAudience.STUDIO && opportunityMinimum > studioRequestMinimum
                && primary.stream().anyMatch(value -> isStudioArtistDiscovery(value.candidate())) ? 1 : 0;
        int opportunityStride = Math.max(1, slots / (opportunityMinimum + 1));
        List<ScoredCandidate> pool = new ArrayList<>(sorted);
        List<ScoredCandidate> selected = new ArrayList<>(slots);
        List<MusicianFeedDeliverySnapshot.OrganicHistoryEntry> recent = new ArrayList<>(history);
        int primaryUsed = 0, discoveryUsed = 0, moduleUsed = 0, systemUsed = 0, opportunitiesUsed = 0;
        int studioRequestsUsed = 0, studioArtistsUsed = 0;
        while (selected.size() < slots) {
            // Reserve opportunities near the front and throughout the page, so
            // later promotion insertion cannot trim all of them from its tail.
            int opportunitiesRemaining = unmetOpportunities(opportunityMinimum, opportunitiesUsed,
                    studioRequestMinimum, studioRequestsUsed, studioArtistMinimum, studioArtistsUsed);
            boolean opportunityDue = opportunitiesRemaining > 0
                    && (primaryCount - primaryUsed <= opportunitiesRemaining
                    || (!selected.isEmpty() && opportunityMinimum - opportunitiesRemaining <= (selected.size() - 1) / opportunityStride));
            int nonModuleRemaining = primaryCount - primaryUsed + discoveryCount - discoveryUsed + systemCount - systemUsed;
            boolean moduleDue = moduleUsed < moduleCount
                    && nonModuleRemaining < moduleCount - moduleUsed
                    && (selected.isEmpty() || selected.getLast().candidate().lane() != MusicianFeedLane.MODULE_SHARE);
            ScoredCandidate best = null;
            for (ScoredCandidate value : pool) {
                MusicianFeedCandidate candidate = value.candidate();
                boolean eligible = switch (candidate.lane()) {
                    case FOLLOWING, RELEVANT_OPPORTUNITY -> primaryUsed < primaryCount;
                    case GENERAL_DISCOVERY -> discoveryUsed < discoveryCount;
                    case SYSTEM -> systemUsed < systemCount && system.contains(value);
                    case MODULE_SHARE -> moduleUsed < moduleCount && chosenModules.contains(value)
                            && (selected.isEmpty() || selected.getLast().candidate().lane() != MusicianFeedLane.MODULE_SHARE);
                };
                if (!eligible || (moduleDue && candidate.lane() != MusicianFeedLane.MODULE_SHARE)
                        || (!moduleDue && opportunityDue && !isRequiredOpportunity(candidate, audience,
                            studioRequestsUsed < studioRequestMinimum, studioArtistsUsed < studioArtistMinimum))) continue;
                if (best == null || compareWithDiversity(value, best, recent) < 0) best = value;
            }
            if (best == null) break;
            selected.add(best);
            pool.remove(best);
            MusicianFeedCandidate candidate = best.candidate();
            switch (candidate.lane()) {
                case FOLLOWING, RELEVANT_OPPORTUNITY -> primaryUsed++;
                case GENERAL_DISCOVERY -> discoveryUsed++;
                case MODULE_SHARE -> moduleUsed++;
                case SYSTEM -> systemUsed++;
            }
            if (isMatchedOpportunity(candidate, audience)) opportunitiesUsed++;
            if (isStudioRequest(candidate)) studioRequestsUsed++;
            if (isStudioArtistDiscovery(candidate)) studioArtistsUsed++;
            rememberOrganic(recent, candidate);
        }
        return List.copyOf(selected);
    }

    private static boolean isMatchedOpportunity(MusicianFeedCandidate candidate, BackstageFeedAudience audience) {
        if (audience == BackstageFeedAudience.STUDIO) {
            return isStudioRequest(candidate) || isStudioArtistDiscovery(candidate);
        }
        if (audience == BackstageFeedAudience.LISTENER) {
            if (candidate.lane() != MusicianFeedLane.FOLLOWING
                    && candidate.lane() != MusicianFeedLane.RELEVANT_OPPORTUNITY) return false;
            return switch (candidate.type()) {
                case TRACK, EVENT, EVENT_PROFILE_SHARE -> true;
                case PROFILE_MEDIA -> candidate.payload() instanceof MusicianFeedPayloads.ProfileMedia media
                        && Set.of("VIDEO", "AUDIO").contains(media.kind());
                default -> false;
            };
        }
        if (audience == BackstageFeedAudience.VENUE) {
            if (candidate.author() == null || !Set.of("MUSICIAN", "BAND").contains(candidate.author().profileType())
                    || (candidate.lane() != MusicianFeedLane.FOLLOWING
                    && candidate.lane() != MusicianFeedLane.RELEVANT_OPPORTUNITY)) return false;
            return switch (candidate.type()) {
                case PROFILE, TRACK, EVENT -> true;
                case PROFILE_MEDIA -> candidate.payload() instanceof MusicianFeedPayloads.ProfileMedia media
                        && Set.of("VIDEO", "AUDIO").contains(media.kind());
                default -> false;
            };
        }
        return (candidate.lane() == MusicianFeedLane.RELEVANT_OPPORTUNITY
                || (candidate.lane() == MusicianFeedLane.FOLLOWING && candidate.relevanceScore() > 0))
                && (candidate.type() == MusicianFeedItemType.COLLAB || candidate.type() == MusicianFeedItemType.EVENT);
    }

    private static int opportunityBudget(int slots, BackstageFeedAudience audience) {
        int divisor = audience == BackstageFeedAudience.STUDIO ? 4 : 5;
        return slots >= divisor ? slots / divisor : 0;
    }

    private static boolean isStudioRequest(MusicianFeedCandidate candidate) {
        return (candidate.lane() == MusicianFeedLane.FOLLOWING || candidate.lane() == MusicianFeedLane.RELEVANT_OPPORTUNITY)
                && candidate.type() == MusicianFeedItemType.COLLAB
                && candidate.payload() instanceof MusicianFeedPayloads.Collab collab
                && collab.listing() != null && collab.listing().wantedType() == CollabWantedType.STUDIO;
    }

    private static boolean isStudioArtistDiscovery(MusicianFeedCandidate candidate) {
        if (candidate.author() == null || candidate.author().followedByViewer()
                || !Set.of("MUSICIAN", "BAND").contains(candidate.author().profileType())
                || candidate.lane() != MusicianFeedLane.RELEVANT_OPPORTUNITY) return false;
        return switch (candidate.type()) {
            case PROFILE, TRACK -> true;
            case PROFILE_MEDIA -> candidate.payload() instanceof MusicianFeedPayloads.ProfileMedia media
                    && Set.of("VIDEO", "AUDIO").contains(media.kind());
            default -> false;
        };
    }

    private static int unmetOpportunities(int minimum, int used, int requestMinimum, int requestsUsed,
                                          int artistMinimum, int artistsUsed) {
        return Math.max(Math.max(0, minimum - used),
                Math.max(0, requestMinimum - requestsUsed) + Math.max(0, artistMinimum - artistsUsed));
    }

    private static boolean isRequiredOpportunity(MusicianFeedCandidate candidate, BackstageFeedAudience audience,
                                                   boolean requireStudioRequest, boolean requireStudioArtist) {
        if (requireStudioRequest) return isStudioRequest(candidate);
        if (requireStudioArtist) return isStudioArtistDiscovery(candidate);
        return isMatchedOpportunity(candidate, audience);
    }

    private List<ScoredCandidate> selectModuleShares(List<ScoredCandidate> sorted, int slots,
                                                       long deliveredOverthinkingShareCount,
                                                       long deliveredTableGroupShareCount) {
        if (slots <= 0 || sorted.isEmpty()) return List.of();
        ScoredCandidate overthinking = sorted.stream()
                .filter(value -> value.candidate().type()
                        == MusicianFeedItemType.OVERTHINKING_PROFILE_SHARE)
                .findFirst().orElse(null);
        ScoredCandidate tableGroup = sorted.stream()
                .filter(value -> value.candidate().type()
                        == MusicianFeedItemType.TABLEGROUP_PROFILE_SHARE)
                .findFirst().orElse(null);
        if (overthinking == null || tableGroup == null) {
            return List.copyOf(sorted.subList(0, Math.min(slots, sorted.size())));
        }
        if (slots == 1) {
            if (deliveredOverthinkingShareCount < deliveredTableGroupShareCount) {
                return List.of(overthinking);
            }
            if (deliveredTableGroupShareCount < deliveredOverthinkingShareCount) {
                return List.of(tableGroup);
            }
            return List.of(SCORED_ORDER.compare(overthinking, tableGroup) <= 0
                    ? overthinking : tableGroup);
        }

        List<ScoredCandidate> selected = new ArrayList<>(slots);
        selected.add(overthinking);
        selected.add(tableGroup);
        for (ScoredCandidate candidate : sorted) {
            if (selected.size() >= slots) break;
            if (!selected.contains(candidate)) selected.add(candidate);
        }
        return List.copyOf(selected);
    }

    private int compareWithDiversity(ScoredCandidate left, ScoredCandidate right,
                                     List<MusicianFeedDeliverySnapshot.OrganicHistoryEntry> recent) {
        int score = Long.compare(diversityAdjusted(right, recent), diversityAdjusted(left, recent));
        return score != 0 ? score : SCORED_ORDER.compare(left, right);
    }

    private long diversityAdjusted(ScoredCandidate candidate,
                                   List<MusicianFeedDeliverySnapshot.OrganicHistoryEntry> selected) {
        long adjusted = candidate.score();
        String author = authorKey(candidate.candidate());
        int from = Math.max(0, selected.size() - 5);
        int authorCount = 0;
        int typeCount = 0;
        for (int index = from; index < selected.size(); index++) {
            var previous = selected.get(index);
            if (author != null && author.equals(previous.authorKey())) authorCount++;
            if (previous.itemType() == candidate.candidate().type()) typeCount++;
        }
        adjusted -= authorCount * 90_000L;
        adjusted -= typeCount * 45_000L;
        if (!selected.isEmpty()) {
            var previous = selected.getLast();
            if (author != null && author.equals(previous.authorKey())) {
                adjusted -= 160_000L;
            }
            if (previous.itemType() == candidate.candidate().type()) adjusted -= 70_000L;
            if (previous.lane() == MusicianFeedLane.MODULE_SHARE
                    && candidate.candidate().lane() == MusicianFeedLane.MODULE_SHARE) {
                adjusted -= 1_000_000_000L;
            }
        }
        return adjusted;
    }

    private MergeResult mergePromotions(
            UUID viewerId,
            Instant anchor,
            List<ScoredCandidate> organic,
            List<ScoredCandidate> organicPool,
            List<ScoredCandidate> sponsors,
            int pageSize,
            long deliveredOrganic,
            long deliveredPromotions,
            boolean previousWasPromotion,
            long organicCountAtLastPromotion,
            Map<UUID, ScoredCandidate> announcements,
            MusicianFeedAnnouncementPlan announcementPlan,
            MusicianFeedDeliverySnapshot deliveryState,
            MusicianFeedItemType previousItemType,
            BackstageFeedAudience audience
    ) {
        organic = new ArrayList<>(organic);
        List<MusicianFeedItemResponse> output = new ArrayList<>(pageSize);
        List<MusicianFeedLane> outputLanes = new ArrayList<>(pageSize);
        int organicIndex = 0;
        int sponsorIndex = 0;
        int organicEmitted = 0;
        Set<String> opportunityTargets = organic.stream().filter(value -> isMatchedOpportunity(value.candidate(), audience))
                .map(value -> targetKey(value.candidate())).collect(java.util.stream.Collectors.toSet());
        int opportunityMinimum = Math.min(opportunityTargets.size(), audience == BackstageFeedAudience.STUDIO
                ? opportunityBudget(pageSize, audience) : Math.max(1, pageSize / 5));
        Set<String> studioRequestTargets = audience != BackstageFeedAudience.STUDIO ? Set.of()
                : organic.stream().filter(value -> isStudioRequest(value.candidate())).map(value -> targetKey(value.candidate()))
                    .collect(java.util.stream.Collectors.toSet());
        Set<String> studioArtistTargets = audience != BackstageFeedAudience.STUDIO ? Set.of()
                : organic.stream().filter(value -> isStudioArtistDiscovery(value.candidate())).map(value -> targetKey(value.candidate()))
                    .collect(java.util.stream.Collectors.toSet());
        int studioRequestMinimum = opportunityMinimum > 0 && !studioRequestTargets.isEmpty() ? 1 : 0;
        int studioArtistMinimum = opportunityMinimum > studioRequestMinimum && !studioArtistTargets.isEmpty() ? 1 : 0;
        int studioRequestsEmitted = 0, studioArtistsEmitted = 0;
        int opportunitiesEmitted = 0;
        long organicSeen = deliveredOrganic;
        long nextPromotionAt = organicCountAtLastPromotion
                + MusicianFeedPromotionCadence.organicGap(viewerId, anchor, deliveredPromotions);
        boolean lastWasPromotion = previousWasPromotion;
        boolean lastWasAnnouncement = previousItemType == MusicianFeedItemType.ANNOUNCEMENT;
        Set<UUID> announcementIds = new HashSet<>(deliveryState == null ? Set.of() : deliveryState.deliveredAnnouncementIds());
        long normalAtLastAnnouncement = deliveryState == null ? 0 : deliveryState.normalCountAtLastAnnouncement();
        List<MusicianFeedAnnouncementPlan.Entry> remainingAnnouncements = announcementPlan.entries().stream()
                .filter(entry -> !announcementIds.contains(entry.id()) && announcements.containsKey(entry.id())).toList();
        int announcementIndex = 0;
        Set<String> deliveredTargets = new HashSet<>();
        Set<String> promotedTargets = new HashSet<>();
        Set<String> emittedAggregationKeys = new HashSet<>();
        Set<String> scheduledItemIds = new HashSet<>();
        organic.forEach(value -> scheduledItemIds.add(value.candidate().itemId()));
        List<MusicianFeedDeliverySnapshot.OrganicHistoryEntry> recent = new ArrayList<>(
                deliveryState == null ? List.of() : deliveryState.recentOrganicHistory());
        Set<String> sponsorTargets = new HashSet<>();
        sponsors.forEach(value -> sponsorTargets.add(targetKey(value.candidate())));
        Deque<ScoredCandidate> deferredNativeTargets = new ArrayDeque<>();
        ScoredCandidate boundaryCandidate = null;
        while (output.size() < pageSize) {
            boolean protectOpportunity = pageSize - output.size() <= unmetOpportunities(opportunityMinimum, opportunitiesEmitted,
                    studioRequestMinimum, studioRequestsEmitted, studioArtistMinimum, studioArtistsEmitted);
            final boolean requireStudioRequest = studioRequestsEmitted < studioRequestMinimum;
            final boolean requireStudioArtist = studioArtistsEmitted < studioArtistMinimum;
            if (protectOpportunity) {
                boolean restored = false;
                for (int index = organicIndex; index < organic.size(); index++) {
                    ScoredCandidate value = organic.get(index);
                    if (isRequiredOpportunity(value.candidate(), audience, requireStudioRequest, requireStudioArtist)
                            && !deliveredTargets.contains(targetKey(value.candidate()))) {
                        Collections.swap(organic, organicIndex, index);
                        restored = true;
                        break;
                    }
                }
                if (!restored) {
                    ScoredCandidate deferredOpportunity = deferredNativeTargets.stream()
                            .filter(value -> isRequiredOpportunity(value.candidate(), audience, requireStudioRequest, requireStudioArtist)
                                    && !deliveredTargets.contains(targetKey(value.candidate())))
                            .findFirst().orElse(null);
                    if (deferredOpportunity != null) {
                        deferredNativeTargets.remove(deferredOpportunity);
                        organic.add(organicIndex, deferredOpportunity);
                    }
                }
            }
            if (announcementIndex < remainingAnnouncements.size()
                    && announcementIds.size() < MusicianFeedAnnouncementPlan.MAX_ANNOUNCEMENTS
                    && !lastWasPromotion && !lastWasAnnouncement && organicSeen >= 1 && !protectOpportunity) {
                var entry = remainingAnnouncements.get(announcementIndex);
                long threshold = announcementThreshold(entry, announcementIds.size(), normalAtLastAnnouncement);
                if (organicSeen >= threshold) {
                    ScoredCandidate announcement = announcements.get(entry.id());
                    output.add(announcement.candidate().toResponse());
                    outputLanes.add(MusicianFeedLane.SYSTEM);
                    deliveredTargets.add(targetKey(announcement.candidate()));
                    emittedAggregationKeys.add(aggregationKey(announcement.candidate()));
                    announcementIds.add(entry.id());
                    announcementIndex++;
                    normalAtLastAnnouncement = organicSeen;
                    lastWasAnnouncement = true;
                    boundaryCandidate = weaker(boundaryCandidate, announcement);
                    continue;
                }
            }
            boolean mayPromote = sponsorIndex < sponsors.size()
                    && organicSeen >= nextPromotionAt
                    && organicSeen >= 2
                    && !lastWasPromotion && !lastWasAnnouncement
                    && (!protectOpportunity || ((requireStudioRequest ? studioRequestTargets : requireStudioArtist ? studioArtistTargets : opportunityTargets)
                    .contains(targetKey(sponsors.get(sponsorIndex).candidate()))
                    && !deliveredTargets.contains(targetKey(sponsors.get(sponsorIndex).candidate()))));
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
                    if (opportunityTargets.contains(targetKey(sponsor.candidate()))) opportunitiesEmitted++;
                    if (studioRequestTargets.contains(targetKey(sponsor.candidate()))) studioRequestsEmitted++;
                    if (studioArtistTargets.contains(targetKey(sponsor.candidate()))) studioArtistsEmitted++;
                    boundaryCandidate = weaker(boundaryCandidate, sponsor);
                    lastWasPromotion = true;
                    lastWasAnnouncement = false;
                    deliveredPromotions++;
                    nextPromotionAt = organicSeen
                            + MusicianFeedPromotionCadence.organicGap(viewerId, anchor, deliveredPromotions);
                    continue;
                }
            }
            if (organicIndex < organic.size()) {
                ScoredCandidate candidate = organic.get(organicIndex++);
                if (candidate.candidate().lane() == MusicianFeedLane.MODULE_SHARE && !outputLanes.isEmpty()
                        && outputLanes.getLast() == MusicianFeedLane.MODULE_SHARE) continue;
                if (promotedTargets.contains(targetKey(candidate.candidate()))
                        || !emittedAggregationKeys.add(aggregationKey(candidate.candidate()))) continue;
                if (!isActivity(candidate.candidate().type())
                        && !protectOpportunity
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
                if (isMatchedOpportunity(candidate.candidate(), audience)) opportunitiesEmitted++;
                if (studioRequestTargets.contains(targetKey(candidate.candidate()))) studioRequestsEmitted++;
                if (studioArtistTargets.contains(targetKey(candidate.candidate()))) studioArtistsEmitted++;
                rememberOrganic(recent, candidate.candidate());
                boundaryCandidate = weaker(boundaryCandidate, candidate);
                lastWasPromotion = false;
                lastWasAnnouncement = false;
            } else if (!deferredNativeTargets.isEmpty()) {
                ScoredCandidate candidate = deferredNativeTargets.removeFirst();
                if (candidate.candidate().lane() == MusicianFeedLane.MODULE_SHARE && !outputLanes.isEmpty()
                        && outputLanes.getLast() == MusicianFeedLane.MODULE_SHARE) continue;
                if (promotedTargets.contains(targetKey(candidate.candidate()))
                        || !emittedAggregationKeys.add(aggregationKey(candidate.candidate()))) continue;
                output.add(candidate.candidate().toResponse());
                outputLanes.add(candidate.candidate().lane());
                deliveredTargets.add(targetKey(candidate.candidate()));
                organicSeen++;
                organicEmitted++;
                if (isMatchedOpportunity(candidate.candidate(), audience)) opportunitiesEmitted++;
                if (studioRequestTargets.contains(targetKey(candidate.candidate()))) studioRequestsEmitted++;
                if (studioArtistTargets.contains(targetKey(candidate.candidate()))) studioArtistsEmitted++;
                rememberOrganic(recent, candidate.candidate());
                boundaryCandidate = weaker(boundaryCandidate, candidate);
                lastWasPromotion = false;
                lastWasAnnouncement = false;
            } else {
                // A promoted target can suppress multiple selected comment
                // stories. Fill those holes from the already-fetched pool;
                // system/module reservations never receive a second budget.
                ScoredCandidate refill = selectBackfill(organicPool, scheduledItemIds,
                        deliveredTargets, promotedTargets, recent);
                if (refill == null) break;
                organic.add(refill);
                scheduledItemIds.add(refill.candidate().itemId());
            }
        }
        Set<String> deliveredItemIds = output.stream().map(MusicianFeedItemResponse::id)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        boolean announcementRemains = announcementIndex < remainingAnnouncements.size()
                && announcementIds.size() < MusicianFeedAnnouncementPlan.MAX_ANNOUNCEMENTS;
        long nextAnnouncementAt = announcementRemains
                ? announcementThreshold(remainingAnnouncements.get(announcementIndex), announcementIds.size(), normalAtLastAnnouncement)
                : Long.MAX_VALUE;
        return new MergeResult(List.copyOf(output), List.copyOf(outputLanes),
                sponsorIndex, organicEmitted, organicSeen,
                nextPromotionAt, lastWasPromotion, deliveredItemIds, Set.copyOf(deliveredTargets),
                Set.copyOf(promotedTargets), boundaryCandidate, announcementRemains, nextAnnouncementAt, lastWasAnnouncement);
    }

    private static long announcementThreshold(MusicianFeedAnnouncementPlan.Entry entry, int deliveredCount,
                                                long normalAtLastAnnouncement) {
        return deliveredCount == 0 ? entry.gap()
                : normalAtLastAnnouncement + Math.max(MusicianFeedAnnouncementPlan.MIN_NORMAL_GAP, entry.gap());
    }

    private boolean announcementCanBeServedLater(MergeResult merged, List<ScoredCandidate> organic) {
        if (!merged.announcementRemains()) return false;
        long requiredNormal = Math.max(0L, merged.nextAnnouncementAt() - merged.organicSeen());
        requiredNormal = Math.max(requiredNormal, Math.max(0L, 1L - merged.organicSeen()));
        if (merged.lastWasPromotion() || merged.lastWasAnnouncement()) requiredNormal = Math.max(1L, requiredNormal);
        return organic.stream().filter(value -> isEligibleContinuation(value.candidate(), merged)).count() >= requiredNormal;
    }

    private boolean isEligibleContinuation(MusicianFeedCandidate candidate, MergeResult merged) {
        if (merged.deliveredItemIds().contains(candidate.itemId())) return false;
        if (candidate.lane() == MusicianFeedLane.MODULE_SHARE && !merged.itemLanes().isEmpty()
                && merged.itemLanes().getLast() == MusicianFeedLane.MODULE_SHARE) return false;
        String target = targetKey(candidate);
        if (merged.promotedTargets().contains(target)) return false;
        return candidate.type() == MusicianFeedItemType.ACTIVITY_COMMENT
                || !merged.deliveredTargets().contains(target);
    }

    private ScoredCandidate selectBackfill(List<ScoredCandidate> pool, Set<String> scheduledItemIds,
                                           Set<String> deliveredTargets, Set<String> promotedTargets,
                                           List<MusicianFeedDeliverySnapshot.OrganicHistoryEntry> recent) {
        ScoredCandidate best = null;
        for (ScoredCandidate value : pool) {
            MusicianFeedCandidate candidate = value.candidate();
            if (candidate.lane() == MusicianFeedLane.SYSTEM || candidate.lane() == MusicianFeedLane.MODULE_SHARE
                    || scheduledItemIds.contains(candidate.itemId())
                    || promotedTargets.contains(targetKey(candidate))
                    || (candidate.type() != MusicianFeedItemType.ACTIVITY_COMMENT
                    && deliveredTargets.contains(targetKey(candidate)))) continue;
            boolean discovery = candidate.lane() == MusicianFeedLane.GENERAL_DISCOVERY;
            boolean bestDiscovery = best != null && best.candidate().lane() == MusicianFeedLane.GENERAL_DISCOVERY;
            if (best == null || (bestDiscovery && !discovery)
                    || (discovery == bestDiscovery && compareWithDiversity(value, best, recent) < 0)) best = value;
        }
        return best;
    }

    private void rememberOrganic(List<MusicianFeedDeliverySnapshot.OrganicHistoryEntry> recent,
                                 MusicianFeedCandidate candidate) {
        if (candidate.lane() == MusicianFeedLane.SYSTEM) return;
        recent.add(new MusicianFeedDeliverySnapshot.OrganicHistoryEntry(authorKey(candidate), candidate.type(), candidate.lane()));
        if (recent.size() > MusicianFeedDeliverySnapshot.ORGANIC_HISTORY_WINDOW) recent.removeFirst();
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
        if (merged.lastWasPromotion() || merged.lastWasAnnouncement()) requiredOrganic = Math.max(requiredOrganic, 1L);
        long remainingOrganic = organic.stream()
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

    private static long conservativeLastPromotionPosition(long organicCount, long promotionCount,
                                                         boolean lastItemPromoted) {
        // Legacy callers do not know when a delayed ad actually appeared.
        // Only the final organic item (if any) is a proven gap. Full-state
        // continuations use the persisted position instead of this fallback.
        return promotionCount == 0 ? 0 : Math.max(0, organicCount - (lastItemPromoted ? 0 : 1));
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
                               int sponsorExamined,
                               int organicEmitted, long organicSeen,
                               long nextPromotionAt, boolean lastWasPromotion,
                               Set<String> deliveredItemIds, Set<String> deliveredTargets, Set<String> promotedTargets,
                               ScoredCandidate lastDelivered, boolean announcementRemains,
                               long nextAnnouncementAt, boolean lastWasAnnouncement) { }

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
