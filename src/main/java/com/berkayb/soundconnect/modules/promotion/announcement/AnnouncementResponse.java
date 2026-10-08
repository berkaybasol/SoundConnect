package com.berkayb.soundconnect.modules.promotion.announcement;

import com.berkayb.soundconnect.modules.media.enums.MediaKind;
import com.berkayb.soundconnect.modules.media.enums.MediaStatus;
import com.berkayb.soundconnect.modules.media.enums.MediaStreamingProtocol;
import com.berkayb.soundconnect.modules.profile.shared.media.enums.ProfileType;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Stable content projection: expiring media access URLs must never enter feed replays. */
public record AnnouncementResponse(
        UUID id, long version, String title, String body, Set<ProfileType> targetProfiles,
        AnnouncementStatus status, Instant startsAt, Instant endsAt, Instant firstPublishedAt,
        Instant createdAt, Instant updatedAt, Media media, Engagement engagement, boolean feedHidden
) {
    public AnnouncementResponse {
        targetProfiles = Set.copyOf(targetProfiles);
    }

    public record Media(UUID assetId, MediaKind kind, MediaStatus status,
                        MediaStreamingProtocol streamingProtocol, Integer width, Integer height,
                        Integer durationSeconds) { }

    public record Engagement(long likeCount, long commentCount, boolean likedByMe) { }
}
