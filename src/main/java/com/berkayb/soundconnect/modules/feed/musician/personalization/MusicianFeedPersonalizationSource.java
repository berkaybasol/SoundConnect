package com.berkayb.soundconnect.modules.feed.musician.personalization;

import java.util.UUID;

/** Small seam that keeps ranking independent from preference storage. */
@FunctionalInterface
public interface MusicianFeedPersonalizationSource {
    MusicianFeedPersonalizationSnapshot load(UUID userId, UUID musicianProfileId);

    /** Venue feeds do not use musician preferences or profile-completion tasks. */
    default MusicianFeedPersonalizationSnapshot loadForVenue(UUID userId, UUID venueId) {
        return MusicianFeedPersonalizationSnapshot.empty();
    }

    /** Studio relevance starts from its business location, without musician completion tasks. */
    default MusicianFeedPersonalizationSnapshot loadForStudio(UUID userId, UUID studioProfileId) {
        return MusicianFeedPersonalizationSnapshot.empty();
    }

    default MusicianFeedPersonalizationSnapshot loadForListener(UUID userId, UUID listenerProfileId) {
        return MusicianFeedPersonalizationSnapshot.empty();
    }
}
