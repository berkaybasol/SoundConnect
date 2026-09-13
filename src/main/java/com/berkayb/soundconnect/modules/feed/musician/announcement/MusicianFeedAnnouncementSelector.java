package com.berkayb.soundconnect.modules.feed.musician.announcement;

import com.berkayb.soundconnect.modules.promotion.announcement.AnnouncementResponse;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Streaming weighted sampling without replacement: constant selection memory for the entire eligible pool. */
final class MusicianFeedAnnouncementSelector {
    private final UUID viewer;
    private final UUID session;
    private final List<Weighted> reservoir = new ArrayList<>(4);
    private AnnouncementResponse newest;
    private long newestQualifiedImpressions;

    MusicianFeedAnnouncementSelector(UUID viewer, UUID session) {
        this.viewer = Objects.requireNonNull(viewer);
        this.session = Objects.requireNonNull(session);
    }

    void consider(AnnouncementResponse value, long qualifiedImpressions) {
        if (qualifiedImpressions < 0 || value == null || value.id() == null || value.firstPublishedAt() == null) {
            throw new IllegalArgumentException("Invalid announcement selection input");
        }
        if (newest == null || NEWEST.compare(value, newest) < 0) {
            newest = value;
            newestQualifiedImpressions = qualifiedImpressions;
        }
        // Exponential races implement weighted selection without iteration-order-dependent RNG state.
        double uniform = Math.max(0x1.0p-53, (seed(value.id(), "selection") >>> 11) * 0x1.0p-53);
        double priority = -Math.log(uniform) * (1d + qualifiedImpressions);
        reservoir.add(new Weighted(value, priority));
        reservoir.sort(Comparator.comparingDouble(Weighted::priority).thenComparing(row -> row.value().id()));
        if (reservoir.size() > MusicianFeedAnnouncementPlan.MAX_ANNOUNCEMENTS) reservoir.removeLast();
    }

    List<Selected> selected() {
        List<AnnouncementResponse> ordered = new ArrayList<>(3);
        // Only the newest eligible announcement owns the first-exposure slot.
        // Older unseen announcements retain their weight but cannot inherit it.
        boolean prioritizeNewest = newest != null && newestQualifiedImpressions == 0;
        if (prioritizeNewest) ordered.add(newest);
        for (Weighted row : reservoir) {
            if (ordered.size() == MusicianFeedAnnouncementPlan.MAX_ANNOUNCEMENTS) break;
            if (ordered.stream().noneMatch(value -> value.id().equals(row.value().id()))) ordered.add(row.value());
        }
        List<Selected> result = new ArrayList<>(ordered.size());
        for (int index = 0; index < ordered.size(); index++) {
            AnnouncementResponse value = ordered.get(index);
            boolean early = index == 0 && prioritizeNewest;
            int minimum = early ? 1 : MusicianFeedAnnouncementPlan.MIN_NORMAL_GAP;
            int maximum = early ? 2 : MusicianFeedAnnouncementPlan.MAX_NORMAL_GAP;
            int gap = minimum + (int) Math.floorMod(seed(value.id(), "gap:" + index), maximum - minimum + 1L);
            result.add(new Selected(value, new MusicianFeedAnnouncementPlan.Entry(value.id(), gap)));
        }
        return List.copyOf(result);
    }

    private long seed(UUID id, String purpose) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(
                    ("announcement-v1|" + viewer + "|" + session + "|" + id + "|" + purpose)
                            .getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(bytes).getLong();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static final Comparator<AnnouncementResponse> NEWEST = Comparator
            .comparing(AnnouncementResponse::firstPublishedAt).reversed()
            .thenComparing(AnnouncementResponse::id);
    private record Weighted(AnnouncementResponse value, double priority) { }
    record Selected(AnnouncementResponse value, MusicianFeedAnnouncementPlan.Entry placement) { }
}
