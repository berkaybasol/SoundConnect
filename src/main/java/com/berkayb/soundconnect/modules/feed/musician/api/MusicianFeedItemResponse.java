package com.berkayb.soundconnect.modules.feed.musician.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record MusicianFeedItemResponse(
        String id,
        MusicianFeedItemType type,
        int payloadVersion,
        Instant occurredAt,
        long position,
        String impressionToken,
        Reason reason,
        Author author,
        Target target,
        Engagement engagement,
        Promotion promotion,
        List<MusicianFeedFeedbackAction> feedbackCapabilities,
        Object payload
) {
    public MusicianFeedItemResponse {
        feedbackCapabilities = feedbackCapabilities == null ? List.of() : List.copyOf(feedbackCapabilities);
    }

    public MusicianFeedItemResponse(
            String id,
            MusicianFeedItemType type,
            int payloadVersion,
            Instant occurredAt,
            Reason reason,
            Author author,
            Target target,
            Engagement engagement,
            Promotion promotion,
            List<MusicianFeedFeedbackAction> feedbackCapabilities,
            Object payload
    ) {
        this(id, type, payloadVersion, occurredAt, -1, null, reason, author, target,
                engagement, promotion, feedbackCapabilities, payload);
    }

    public MusicianFeedItemResponse withDelivery(long absolutePosition, String token) {
        return new MusicianFeedItemResponse(id, type, payloadVersion, occurredAt, absolutePosition,
                token, reason, author, target, engagement, promotion, feedbackCapabilities, payload);
    }

    public record Author(
            UUID userId,
            UUID profileId,
            String profileType,
            String username,
            String displayName,
            String avatarUrl,
            boolean followedByViewer
    ) { }

    public record Target(String type, UUID id) { }

    public record Reason(
            MusicianFeedReasonCode code,
            List<Author> actors,
            int secondaryActorCount
    ) {
        public Reason {
            actors = actors == null ? List.of() : List.copyOf(actors);
        }
    }

    public record Engagement(
            String targetType,
            UUID targetId,
            long likeCount,
            long commentCount,
            boolean likedByMe,
            boolean likable,
            boolean commentable
    ) { }

    public record Promotion(
            UUID campaignId,
            String disclosure,
            String ctaLabel,
            String ctaUrl
    ) { }
}
