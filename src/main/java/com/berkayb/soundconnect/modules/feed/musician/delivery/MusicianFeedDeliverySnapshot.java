package com.berkayb.soundconnect.modules.feed.musician.delivery;

import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedItemType;
import com.berkayb.soundconnect.modules.feed.musician.candidate.MusicianFeedLane;

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
        MusicianFeedLane lastItemLane
) {
    public MusicianFeedDeliverySnapshot {
        itemIds = itemIds == null ? Set.of() : Set.copyOf(itemIds);
        targetKeys = targetKeys == null ? Set.of() : Set.copyOf(targetKeys);
        organicTargetKeys = organicTargetKeys == null ? Set.of() : Set.copyOf(organicTargetKeys);
        promotedTargetKeys = promotedTargetKeys == null ? Set.of() : Set.copyOf(promotedTargetKeys);
        campaignIds = campaignIds == null ? Set.of() : Set.copyOf(campaignIds);
        if (nextAbsolutePosition < 0) throw new IllegalArgumentException("Negative feed position");
        if (organicCountAtLastPromotion < 0) throw new IllegalArgumentException("Negative promotion cadence");
    }

    public static MusicianFeedDeliverySnapshot empty(long nextAbsolutePosition) {
        return new MusicianFeedDeliverySnapshot(Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
                nextAbsolutePosition, 0, 0, false, null, null);
    }

    public MusicianFeedDeliverySnapshot(Set<String> itemIds, Set<String> targetKeys,
                                        Set<String> promotedTargetKeys, Set<UUID> campaignIds,
                                        long nextAbsolutePosition, long deliveredPromotionCount,
                                        boolean lastItemPromoted) {
        this(itemIds, targetKeys, targetKeys, promotedTargetKeys, campaignIds, nextAbsolutePosition,
                deliveredPromotionCount, deliveredPromotionCount * 8, lastItemPromoted, null, null);
    }

    public static String targetKey(String type, UUID id) {
        return type + ":" + id;
    }
}
