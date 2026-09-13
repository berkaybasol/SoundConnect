package com.berkayb.soundconnect.modules.promotion.announcement;

import java.util.List;

public record AnnouncementPage(List<AnnouncementResponse> items, String nextCursor, boolean hasMore) {
    public AnnouncementPage { items = List.copyOf(items); }
}
