package com.berkayb.soundconnect.modules.feed.musician.personalization;

import java.util.UUID;

/** Small seam that keeps ranking independent from preference storage. */
@FunctionalInterface
public interface MusicianFeedPersonalizationSource {
    MusicianFeedPersonalizationSnapshot load(UUID userId, UUID musicianProfileId);
}
