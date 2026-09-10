package com.berkayb.soundconnect.modules.tablegroup.profileshare;

import com.berkayb.soundconnect.modules.tablegroup.enums.TableGroupStatus;

import java.time.Instant;
import java.util.UUID;

public final class TableGroupProfileShareResponse {
    private TableGroupProfileShareResponse() { }

    public record State(UUID tableGroupId, UUID shareId, boolean publishedOnProfile, String note,
                        Instant publishedAt, boolean canPublish, Source tableGroup) { }

    public record Post(UUID shareId, String note, Instant publishedAt, Source tableGroup,
                       long likeCount, long commentCount, boolean likedByMe) { }

    /** Public card metadata only: never exposes applications, join notes, chat or member identities. */
    public record Source(UUID id, String description, String venueName, String cityName, String districtName,
                         Instant meetingAt, Instant expiresAt, TableGroupStatus status,
                         int maxPersonCount, long acceptedCount) { }
}
