package com.berkayb.soundconnect.modules.feed.musician.feedback;

import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedItemType;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

public record MusicianFeedFeedbackSnapshot(
        Set<String> hiddenItemIds,
        Set<String> mutedAuthorKeys,
        Map<MusicianFeedItemType, Integer> showLessCounts,
        String rankingContextVersion
) {
    public MusicianFeedFeedbackSnapshot(Set<String> hiddenItemIds, Set<String> mutedAuthorKeys,
                                        Map<MusicianFeedItemType, Integer> showLessCounts) {
        this(hiddenItemIds, mutedAuthorKeys, showLessCounts, "0");
    }

    public MusicianFeedFeedbackSnapshot {
        hiddenItemIds = hiddenItemIds == null ? Set.of() : Set.copyOf(hiddenItemIds);
        mutedAuthorKeys = mutedAuthorKeys == null ? Set.of() : Set.copyOf(mutedAuthorKeys);
        showLessCounts = showLessCounts == null ? Map.of() : Map.copyOf(showLessCounts);
        rankingContextVersion = rankingContextVersion == null ? "0" : rankingContextVersion;
    }

    public static MusicianFeedFeedbackSnapshot empty() {
        return new MusicianFeedFeedbackSnapshot(Set.of(), Set.of(), Map.of(), "0");
    }

    public static String authorKey(String profileType, UUID profileId) {
        return profileType + ":" + profileId;
    }
}
