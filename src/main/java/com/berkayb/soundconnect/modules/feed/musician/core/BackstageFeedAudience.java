package com.berkayb.soundconnect.modules.feed.musician.core;

/** The viewer role changes selection, not the shared publication/engagement contract. */
public enum BackstageFeedAudience {
    MUSICIAN,
    VENUE,
    STUDIO,
    LISTENER;

    public String algorithmVersion() {
        return switch (this) {
            case MUSICIAN -> MusicianFeedService.ALGORITHM_VERSION;
            case VENUE -> "venue-v1.0.0";
            case STUDIO -> "studio-v1.0.0";
            case LISTENER -> "listener-v1.0.0";
        };
    }
}
