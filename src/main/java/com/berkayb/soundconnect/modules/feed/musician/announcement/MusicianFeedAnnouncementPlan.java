package com.berkayb.soundconnect.modules.feed.musician.announcement;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/** A small immutable selection, signed with the feed cursor and never redrawn by later impressions. */
public record MusicianFeedAnnouncementPlan(List<Entry> entries) {
    public static final int MAX_ANNOUNCEMENTS = 3;
    public static final int MIN_NORMAL_GAP = 4;
    public static final int MAX_NORMAL_GAP = 8;
    public static final MusicianFeedAnnouncementPlan EMPTY = new MusicianFeedAnnouncementPlan(List.of());

    public MusicianFeedAnnouncementPlan {
        if (entries == null || entries.size() > MAX_ANNOUNCEMENTS) throw invalid();
        entries = List.copyOf(entries);
        var ids = new HashSet<UUID>();
        for (int index = 0; index < entries.size(); index++) {
            Entry entry = entries.get(index);
            if (entry == null || entry.id() == null || !ids.add(entry.id())
                    || entry.gap() < (index == 0 ? 1 : MIN_NORMAL_GAP)
                    || (index == 0 && entry.gap() == 3)
                    || entry.gap() > MAX_NORMAL_GAP) throw invalid();
        }
    }

    public record Entry(UUID id, int gap) { }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid musician-feed announcement plan");
    }
}
