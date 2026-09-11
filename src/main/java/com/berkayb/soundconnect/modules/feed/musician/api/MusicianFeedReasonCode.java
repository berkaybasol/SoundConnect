package com.berkayb.soundconnect.modules.feed.musician.api;

/** Machine-readable reason keys; user-facing copy belongs to the client. */
public enum MusicianFeedReasonCode {
    FOLLOWING_PUBLICATION,
    FOLLOWED_USER_COMMENTED,
    FOLLOWED_USER_LIKED,
    FOLLOWED_USER_FOLLOWED,
    CITY_AND_INSTRUMENT_MATCH,
    CITY_MATCH,
    INSTRUMENT_MATCH,
    DISCOVERY,
    PROFILE_INCOMPLETE,
    SPONSORED,
    FEATURED,
    PLATFORM_ANNOUNCEMENT
}
