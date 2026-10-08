package com.berkayb.soundconnect.modules.feed.musician.sponsor;

import java.time.Instant;
import java.util.Objects;
import java.util.SplittableRandom;
import java.util.UUID;

/** Stable within a feed session, varied across placements and fresh sessions. */
public final class MusicianFeedPromotionCadence {
    public static final int MIN_ORGANIC_ITEMS = 6;
    public static final int MAX_ORGANIC_ITEMS = 10;

    private MusicianFeedPromotionCadence() { }

    public static int organicGap(UUID viewerId, Instant anchor, long deliveredPromotionCount) {
        Objects.requireNonNull(viewerId, "viewerId");
        Objects.requireNonNull(anchor, "anchor");
        if (deliveredPromotionCount < 0) throw new IllegalArgumentException("Negative promotion count");
        // No request-time randomness: splitting a page or retrying its cursor
        // must not redraw the slot and create clusters at page boundaries.
        long seed = viewerId.getMostSignificantBits()
                ^ Long.rotateLeft(viewerId.getLeastSignificantBits(), 23)
                ^ Long.rotateLeft(anchor.getEpochSecond(), 37)
                ^ anchor.getNano()
                ^ (deliveredPromotionCount * 0x9E3779B97F4A7C15L);
        return new SplittableRandom(seed).nextInt(MIN_ORGANIC_ITEMS, MAX_ORGANIC_ITEMS + 1);
    }

}
