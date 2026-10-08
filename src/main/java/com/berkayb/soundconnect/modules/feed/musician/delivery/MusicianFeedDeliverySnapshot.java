package com.berkayb.soundconnect.modules.feed.musician.delivery;

import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedItemType;
import com.berkayb.soundconnect.modules.feed.musician.candidate.MusicianFeedLane;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public record MusicianFeedDeliverySnapshot(
        Set<String> itemIds,
        Set<String> targetKeys,
        Set<String> organicTargetKeys,
        Set<String> promotedTargetKeys,
        Set<UUID> campaignIds,
        long nextAbsolutePosition,
        long deliveredPromotionCount,
        long organicCountAtLastPromotion,
        boolean lastItemPromoted,
        MusicianFeedItemType lastItemType,
        MusicianFeedLane lastItemLane,
        long deliveredOverthinkingShareCount,
        long deliveredTableGroupShareCount,
        Set<UUID> deliveredAnnouncementIds,
        long deliveredNormalCount,
        long normalCountAtLastAnnouncement,
        Set<String> recentlyViewedTargetKeys,
        List<OrganicHistoryEntry> recentOrganicHistory
) {
    public static final int ORGANIC_HISTORY_WINDOW = 5;

    /** Delivery order, oldest to newest; system and promotional cards do not reset organic diversity. */
    public record OrganicHistoryEntry(String authorKey, MusicianFeedItemType itemType, MusicianFeedLane lane) {
        public OrganicHistoryEntry {
            if (itemType == null || lane == null) throw new IllegalArgumentException("Missing organic history identity");
        }
    }

    public MusicianFeedDeliverySnapshot(Set<String> itemIds, Set<String> targetKeys,
                                        Set<String> organicTargetKeys, Set<String> promotedTargetKeys,
                                        Set<UUID> campaignIds, long nextAbsolutePosition, long deliveredPromotionCount,
                                        long organicCountAtLastPromotion, boolean lastItemPromoted,
                                        MusicianFeedItemType lastItemType, MusicianFeedLane lastItemLane,
                                        long deliveredOverthinkingShareCount, long deliveredTableGroupShareCount,
                                        Set<UUID> deliveredAnnouncementIds, long deliveredNormalCount,
                                        long normalCountAtLastAnnouncement) {
        this(itemIds, targetKeys, organicTargetKeys, promotedTargetKeys, campaignIds,
                nextAbsolutePosition, deliveredPromotionCount, organicCountAtLastPromotion,
                lastItemPromoted, lastItemType, lastItemLane, deliveredOverthinkingShareCount,
                deliveredTableGroupShareCount, deliveredAnnouncementIds, deliveredNormalCount,
                normalCountAtLastAnnouncement, Set.of(), List.of());
    }

    public MusicianFeedDeliverySnapshot(Set<String> itemIds, Set<String> targetKeys,
                                        Set<String> organicTargetKeys, Set<String> promotedTargetKeys,
                                        Set<UUID> campaignIds, long nextAbsolutePosition, long deliveredPromotionCount,
                                        long organicCountAtLastPromotion, boolean lastItemPromoted,
                                        MusicianFeedItemType lastItemType, MusicianFeedLane lastItemLane,
                                        long deliveredOverthinkingShareCount, long deliveredTableGroupShareCount) {
        this(itemIds, targetKeys, organicTargetKeys, promotedTargetKeys, campaignIds,
                nextAbsolutePosition, deliveredPromotionCount, organicCountAtLastPromotion,
                lastItemPromoted, lastItemType, lastItemLane, deliveredOverthinkingShareCount,
                deliveredTableGroupShareCount, Set.of(), Math.max(0, nextAbsolutePosition - deliveredPromotionCount), 0);
    }

    public MusicianFeedDeliverySnapshot {
        itemIds = itemIds == null ? Set.of() : Set.copyOf(itemIds);
        targetKeys = targetKeys == null ? Set.of() : Set.copyOf(targetKeys);
        organicTargetKeys = organicTargetKeys == null ? Set.of() : Set.copyOf(organicTargetKeys);
        promotedTargetKeys = promotedTargetKeys == null ? Set.of() : Set.copyOf(promotedTargetKeys);
        campaignIds = campaignIds == null ? Set.of() : Set.copyOf(campaignIds);
        deliveredAnnouncementIds = deliveredAnnouncementIds == null ? Set.of() : Set.copyOf(deliveredAnnouncementIds);
        recentlyViewedTargetKeys = recentlyViewedTargetKeys == null ? Set.of() : Set.copyOf(recentlyViewedTargetKeys);
        recentOrganicHistory = recentOrganicHistory == null ? List.of() : List.copyOf(recentOrganicHistory);
        if (recentOrganicHistory.size() > ORGANIC_HISTORY_WINDOW) {
            recentOrganicHistory = List.copyOf(recentOrganicHistory.subList(
                    recentOrganicHistory.size() - ORGANIC_HISTORY_WINDOW, recentOrganicHistory.size()));
        }
        if (deliveredNormalCount < 0 || normalCountAtLastAnnouncement < 0
                || normalCountAtLastAnnouncement > deliveredNormalCount) {
            throw new IllegalArgumentException("Invalid announcement cadence state");
        }
        if (nextAbsolutePosition < 0) throw new IllegalArgumentException("Negative feed position");
        if (organicCountAtLastPromotion < 0) throw new IllegalArgumentException("Negative promotion cadence");
        if (deliveredOverthinkingShareCount < 0 || deliveredTableGroupShareCount < 0) {
            throw new IllegalArgumentException("Negative module-share delivery count");
        }
    }

    public static MusicianFeedDeliverySnapshot empty(long nextAbsolutePosition) {
        return new MusicianFeedDeliverySnapshot(Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
                nextAbsolutePosition, 0, 0, false, null, null, 0, 0);
    }

    public MusicianFeedDeliverySnapshot(Set<String> itemIds, Set<String> targetKeys,
                                        Set<String> organicTargetKeys, Set<String> promotedTargetKeys,
                                        Set<UUID> campaignIds, long nextAbsolutePosition,
                                        long deliveredPromotionCount, long organicCountAtLastPromotion,
                                        boolean lastItemPromoted, MusicianFeedItemType lastItemType,
                                        MusicianFeedLane lastItemLane) {
        this(itemIds, targetKeys, organicTargetKeys, promotedTargetKeys, campaignIds,
                nextAbsolutePosition, deliveredPromotionCount, organicCountAtLastPromotion,
                lastItemPromoted, lastItemType, lastItemLane, 0, 0);
    }

    public MusicianFeedDeliverySnapshot(Set<String> itemIds, Set<String> targetKeys,
                                        Set<String> promotedTargetKeys, Set<UUID> campaignIds,
                                        long nextAbsolutePosition, long deliveredPromotionCount,
                                        boolean lastItemPromoted) {
        this(itemIds, targetKeys, targetKeys, promotedTargetKeys, campaignIds, nextAbsolutePosition,
                deliveredPromotionCount, deliveredPromotionCount * 8, lastItemPromoted, null, null,
                0, 0);
    }

    public static String targetKey(String type, UUID id) {
        return type + ":" + id;
    }

    public MusicianFeedDeliverySnapshot withRecentlyViewedTargetKeys(Set<String> viewedTargetKeys) {
        return new MusicianFeedDeliverySnapshot(itemIds, targetKeys, organicTargetKeys, promotedTargetKeys,
                campaignIds, nextAbsolutePosition, deliveredPromotionCount, organicCountAtLastPromotion,
                lastItemPromoted, lastItemType, lastItemLane, deliveredOverthinkingShareCount,
                deliveredTableGroupShareCount, deliveredAnnouncementIds, deliveredNormalCount,
                normalCountAtLastAnnouncement, viewedTargetKeys, recentOrganicHistory);
    }
}
