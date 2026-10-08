package com.berkayb.soundconnect.modules.feed.musician.sponsor;

import com.berkayb.soundconnect.modules.feed.musician.candidate.MusicianFeedCandidate;
import com.berkayb.soundconnect.modules.feed.musician.candidate.MusicianFeedCandidateRequest;
import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedItemType;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Independent campaign seam; sponsorship wraps native targets rather than copying them. */
public interface MusicianFeedSponsorshipProvider {
    String providerId();
    default Set<MusicianFeedItemType> supportedTypes() {
        return Set.copyOf(EnumSet.allOf(MusicianFeedItemType.class));
    }
    List<MusicianFeedCandidate> findPlacements(MusicianFeedCandidateRequest request);

    default boolean optional() { return true; }
}
