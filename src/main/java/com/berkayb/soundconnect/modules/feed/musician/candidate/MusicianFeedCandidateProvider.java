package com.berkayb.soundconnect.modules.feed.musician.candidate;

import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedItemType;

import java.util.List;
import java.util.Set;

/** Bounded, authorization-aware source extension point. */
public interface MusicianFeedCandidateProvider {
    String providerId();
    Set<MusicianFeedItemType> supportedTypes();
    List<MusicianFeedCandidate> findCandidates(MusicianFeedCandidateRequest request);

    default boolean optional() {
        return true;
    }
}
