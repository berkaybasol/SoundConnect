package com.berkayb.soundconnect.modules.feed.musician.candidate;

import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedItemType;
import com.berkayb.soundconnect.modules.feed.musician.feedback.MusicianFeedFeedbackSnapshot;
import com.berkayb.soundconnect.modules.feed.musician.delivery.MusicianFeedDeliverySnapshot;
import com.berkayb.soundconnect.modules.feed.musician.personalization.MusicianFeedPersonalizationSnapshot;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record MusicianFeedCandidateRequest(
        UUID viewerUserId,
        UUID musicianProfileId,
        UUID feedSessionId,
        Instant anchor,
        Instant readAt,
        int limit,
        Set<MusicianFeedItemType> supportedTypes,
        MusicianFeedPersonalizationSnapshot personalization,
        MusicianFeedFeedbackSnapshot feedback,
        MusicianFeedDeliverySnapshot delivery
) {
    public MusicianFeedCandidateRequest(UUID viewerUserId, UUID musicianProfileId, UUID feedSessionId,
                                        Instant anchor, Instant readAt, int limit,
                                        Set<MusicianFeedItemType> supportedTypes,
                                        MusicianFeedPersonalizationSnapshot personalization,
                                        MusicianFeedFeedbackSnapshot feedback) {
        this(viewerUserId, musicianProfileId, feedSessionId, anchor, readAt, limit,
                supportedTypes, personalization, feedback, MusicianFeedDeliverySnapshot.empty(0));
    }

    public MusicianFeedCandidateRequest {
        supportedTypes = Set.copyOf(supportedTypes);
        delivery = delivery == null ? MusicianFeedDeliverySnapshot.empty(0) : delivery;
    }
}
