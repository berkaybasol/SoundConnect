package com.berkayb.soundconnect.modules.feed.musician.candidate;

import com.berkayb.soundconnect.modules.feed.musician.api.*;

import java.time.Instant;
import java.util.List;

public record MusicianFeedCandidate(
        String itemId,
        MusicianFeedItemType type,
        int payloadVersion,
        Instant occurredAt,
        MusicianFeedItemResponse.Reason reason,
        MusicianFeedItemResponse.Author author,
        MusicianFeedItemResponse.Target target,
        MusicianFeedItemResponse.Engagement engagement,
        MusicianFeedItemResponse.Promotion promotion,
        List<MusicianFeedFeedbackAction> feedbackCapabilities,
        Object payload,
        long baseScore,
        int relevanceScore,
        MusicianFeedLane lane,
        boolean ownedByViewer
) {
    public MusicianFeedCandidate {
        if (itemId == null || itemId.isBlank() || type == null || occurredAt == null || target == null) {
            throw new IllegalArgumentException("Feed candidate identity, type, time and target are required");
        }
        feedbackCapabilities = feedbackCapabilities == null ? List.of() : List.copyOf(feedbackCapabilities);
        lane = lane == null ? MusicianFeedLane.GENERAL_DISCOVERY : lane;
    }

    public MusicianFeedItemResponse toResponse() {
        return new MusicianFeedItemResponse(itemId, type, payloadVersion, occurredAt, reason, author,
                target, engagement, promotion, feedbackCapabilities, payload);
    }
}
