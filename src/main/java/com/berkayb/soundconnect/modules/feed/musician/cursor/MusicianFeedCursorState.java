package com.berkayb.soundconnect.modules.feed.musician.cursor;

import com.berkayb.soundconnect.modules.feed.musician.announcement.MusicianFeedAnnouncementPlan;

import java.time.Instant;
import java.util.UUID;

public record MusicianFeedCursorState(
        UUID viewerUserId,
        UUID feedSessionId,
        Instant anchor,
        CursorPosition after,
        long deliveredOrganicCount,
        long deliveredItemCount,
        String rankingContextVersion,
        MusicianFeedAnnouncementPlan announcementPlan
) {
    public MusicianFeedCursorState(UUID viewerUserId, UUID feedSessionId, Instant anchor,
                                   CursorPosition after, long deliveredOrganicCount, long deliveredItemCount,
                                   String rankingContextVersion) {
        this(viewerUserId, feedSessionId, anchor, after, deliveredOrganicCount, deliveredItemCount,
                rankingContextVersion, MusicianFeedAnnouncementPlan.EMPTY);
    }

    public MusicianFeedCursorState {
        announcementPlan = announcementPlan == null ? MusicianFeedAnnouncementPlan.EMPTY : announcementPlan;
    }

    public MusicianFeedCursorState(UUID viewerUserId, UUID feedSessionId, Instant anchor,
                                   CursorPosition after, long deliveredOrganicCount) {
        this(viewerUserId, feedSessionId, anchor, after, deliveredOrganicCount,
                deliveredOrganicCount, "0");
    }

    public record CursorPosition(long rankKey, Instant occurredAt, String itemId) { }
}
