package com.berkayb.soundconnect.modules.feed.musician.candidate;

import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedItemType;
import com.berkayb.soundconnect.modules.feed.musician.feedback.MusicianFeedFeedbackSnapshot;
import com.berkayb.soundconnect.modules.feed.musician.delivery.MusicianFeedDeliverySnapshot;
import com.berkayb.soundconnect.modules.feed.musician.personalization.MusicianFeedPersonalizationSnapshot;
import com.berkayb.soundconnect.modules.feed.musician.announcement.MusicianFeedAnnouncementPlan;
import com.berkayb.soundconnect.modules.feed.musician.core.BackstageFeedAudience;

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
        MusicianFeedDeliverySnapshot delivery,
        MusicianFeedAnnouncementPlan announcementPlan,
        long providerDeadlineNanos,
        BackstageFeedAudience audience
) {
    public MusicianFeedCandidateRequest(UUID viewerUserId, UUID musicianProfileId, UUID feedSessionId,
                                        Instant anchor, Instant readAt, int limit,
                                        Set<MusicianFeedItemType> supportedTypes,
                                        MusicianFeedPersonalizationSnapshot personalization,
                                        MusicianFeedFeedbackSnapshot feedback, MusicianFeedDeliverySnapshot delivery,
                                        MusicianFeedAnnouncementPlan announcementPlan, long providerDeadlineNanos) {
        this(viewerUserId, musicianProfileId, feedSessionId, anchor, readAt, limit,
                supportedTypes, personalization, feedback, delivery, announcementPlan, providerDeadlineNanos,
                BackstageFeedAudience.MUSICIAN);
    }

    /** For VENUE the legacy profile-id field carries the canonical venue target ID. */
    public UUID viewerProfileId() { return musicianProfileId; }

    public MusicianFeedCandidateRequest withAudience(BackstageFeedAudience requestedAudience) {
        return new MusicianFeedCandidateRequest(viewerUserId, musicianProfileId, feedSessionId,
                anchor, readAt, limit, supportedTypes, personalization, feedback, delivery,
                announcementPlan, providerDeadlineNanos, requestedAudience);
    }
    public MusicianFeedCandidateRequest(UUID viewerUserId, UUID musicianProfileId, UUID feedSessionId,
                                        Instant anchor, Instant readAt, int limit,
                                        Set<MusicianFeedItemType> supportedTypes,
                                        MusicianFeedPersonalizationSnapshot personalization,
                                        MusicianFeedFeedbackSnapshot feedback, MusicianFeedDeliverySnapshot delivery,
                                        MusicianFeedAnnouncementPlan announcementPlan) {
        this(viewerUserId, musicianProfileId, feedSessionId, anchor, readAt, limit,
                supportedTypes, personalization, feedback, delivery, announcementPlan, Long.MAX_VALUE);
    }

    public MusicianFeedCandidateRequest withProviderDeadline(long deadlineNanos) {
        return new MusicianFeedCandidateRequest(viewerUserId, musicianProfileId, feedSessionId,
                anchor, readAt, limit, supportedTypes, personalization, feedback, delivery,
                announcementPlan, deadlineNanos, audience);
    }

    public MusicianFeedCandidateRequest(UUID viewerUserId, UUID musicianProfileId, UUID feedSessionId,
                                        Instant anchor, Instant readAt, int limit,
                                        Set<MusicianFeedItemType> supportedTypes,
                                        MusicianFeedPersonalizationSnapshot personalization,
                                        MusicianFeedFeedbackSnapshot feedback, MusicianFeedDeliverySnapshot delivery) {
        this(viewerUserId, musicianProfileId, feedSessionId, anchor, readAt, limit,
                supportedTypes, personalization, feedback, delivery, null);
    }

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
        audience = java.util.Objects.requireNonNull(audience, "audience");
    }
}
