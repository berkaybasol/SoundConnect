package com.berkayb.soundconnect.modules.feed.musician.moderation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class MusicianFeedOrphanRestrictionModels {
    private MusicianFeedOrphanRestrictionModels() { }

    public record Item(UUID reportId, String scopeDescription, String scopeKey, UUID appliedByUserId,
                       Instant appliedAt, Instant updatedAt) { }
    public record Page(List<Item> items, String nextCursor, boolean hasMore) { }
    public record RestoreRequest(@NotNull UUID clientRequestId, @NotNull Instant expectedUpdatedAt,
                                 @NotBlank @Size(min = 5, max = 500) String resolutionNote) { }
    public record Restored(UUID reportId, boolean active, Instant updatedAt, boolean activeRestriction) { }
}
