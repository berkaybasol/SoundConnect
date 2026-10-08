package com.berkayb.soundconnect.modules.like.dto;

import com.berkayb.soundconnect.modules.comment.dto.support.UserSummaryDto;
import java.util.List;

public record LikeUsersPage(List<UserSummaryDto> items, String nextCursor, boolean hasMore) {
    public LikeUsersPage {
        items = List.copyOf(items);
    }
}
