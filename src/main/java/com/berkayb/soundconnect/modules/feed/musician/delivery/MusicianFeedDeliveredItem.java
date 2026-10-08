package com.berkayb.soundconnect.modules.feed.musician.delivery;

import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedFeedbackAction;
import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedItemType;
import com.berkayb.soundconnect.modules.feed.musician.candidate.MusicianFeedLane;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record MusicianFeedDeliveredItem(
        UUID deliveryId,
        UUID viewerUserId,
        UUID feedSessionId,
        String itemId,
        MusicianFeedItemType itemType,
        String targetType,
        UUID targetId,
        String authorProfileType,
        UUID authorProfileId,
        String reasonCode,
        Set<MusicianFeedFeedbackAction> feedbackCapabilities,
        int schemaVersion,
        String algorithmVersion,
        long absolutePosition,
        UUID campaignId,
        String evidenceJson,
        Instant deliveredAt,
        Instant expiresAt,
        Instant purgeAfter,
        MusicianFeedLane lane
) {
    public MusicianFeedDeliveredItem(
            UUID deliveryId, UUID viewerUserId, UUID feedSessionId, String itemId,
            MusicianFeedItemType itemType, String targetType, UUID targetId,
            String authorProfileType, UUID authorProfileId, String reasonCode,
            Set<MusicianFeedFeedbackAction> feedbackCapabilities, int schemaVersion,
            String algorithmVersion, long absolutePosition, UUID campaignId,
            String evidenceJson, Instant deliveredAt, Instant expiresAt, Instant purgeAfter
    ) {
        this(deliveryId, viewerUserId, feedSessionId, itemId, itemType, targetType, targetId,
                authorProfileType, authorProfileId, reasonCode, feedbackCapabilities, schemaVersion,
                algorithmVersion, absolutePosition, campaignId, evidenceJson, deliveredAt,
                expiresAt, purgeAfter, MusicianFeedLane.FOLLOWING);
    }
}
