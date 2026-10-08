package com.berkayb.soundconnect.modules.feed.musician.moderation;

import java.util.List;

public record MusicianFeedReportPage(List<MusicianFeedReportSummary> items, String nextCursor, boolean hasMore) {
    public MusicianFeedReportPage { items = List.copyOf(items); }
}
