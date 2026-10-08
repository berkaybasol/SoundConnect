package com.berkayb.soundconnect.modules.feed.musician.personalization;

import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedPayloads;

import java.util.Set;
import java.util.UUID;

public record MusicianFeedPersonalizationSnapshot(
        UUID opportunityCityId,
        Set<UUID> instrumentIds,
        MusicianFeedPayloads.Completion completion
) {
    public MusicianFeedPersonalizationSnapshot {
        instrumentIds = instrumentIds == null ? Set.of() : Set.copyOf(instrumentIds);
    }

    public static MusicianFeedPersonalizationSnapshot empty() {
        return new MusicianFeedPersonalizationSnapshot(null, Set.of(), null);
    }
}
