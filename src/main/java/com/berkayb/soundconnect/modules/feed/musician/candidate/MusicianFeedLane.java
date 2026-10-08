package com.berkayb.soundconnect.modules.feed.musician.candidate;

/** Product-level mixing lane; visibility remains a provider hard-filter. */
public enum MusicianFeedLane {
    FOLLOWING,
    RELEVANT_OPPORTUNITY,
    GENERAL_DISCOVERY,
    /** Followed-listener wrapper shares which must remain deliberately sparse. */
    MODULE_SHARE,
    SYSTEM
}
