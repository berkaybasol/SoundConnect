package com.berkayb.soundconnect.modules.like.service;

import com.berkayb.soundconnect.modules.comment.dto.support.UserSummaryDto;
import com.berkayb.soundconnect.modules.comment.support.CommentAuthorBatchResolver;
import com.berkayb.soundconnect.modules.engagement.enums.EngagementTargetType;
import com.berkayb.soundconnect.modules.engagement.service.EngagementTargetValidator;
import com.berkayb.soundconnect.modules.like.dto.LikeUsersPage;
import com.berkayb.soundconnect.modules.like.repository.LikeRepository;
import com.berkayb.soundconnect.modules.like.repository.LikeUsersReadRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class LikeUsersReadService {
    private final LikeRepository likes;
    private final LikeUsersReadRepository repository;
    private final EngagementTargetValidator targets;
    private final CommentLikeAccessGuard comments;
    private final CommentAuthorBatchResolver identities;

    // Existing target readers use shared deletion/privacy fences, requiring a writable transaction.
    @Transactional
    public LikeUsersPage get(UUID viewerId, EngagementTargetType type, UUID targetId, Integer requestedSize, String encodedCursor) {
        if (viewerId == null) throw new SoundConnectException(ErrorType.UNAUTHORIZED);
        int size = requestedSize == null ? 20 : requestedSize;
        if (type == null || targetId == null || size < 1 || size > 50) {
            throw new SoundConnectException(ErrorType.INVALID_PARAMETER);
        }
        LikeUsersCursor cursor = LikeUsersCursor.decode(encodedCursor, type, targetId);
        if (likes.lockActiveActor(viewerId).isEmpty()) throw new SoundConnectException(ErrorType.UNAUTHORIZED);
        if (type == EngagementTargetType.COMMENT) comments.requireLikeable(targetId, false);
        else targets.validateExists(type, targetId);

        var rows = repository.page(type, targetId, cursor, size + 1);
        boolean hasMore = rows.size() > size;
        var visible = rows.subList(0, Math.min(rows.size(), size));
        var summaries = identities.resolve(visible.stream().map(LikeUsersReadRepository.Row::userId).toList());
        List<UserSummaryDto> items = visible.stream().map(row -> summaries.get(row.userId()))
                .filter(Objects::nonNull).toList();
        String nextCursor = null;
        if (hasMore) {
            var last = visible.getLast();
            nextCursor = new LikeUsersCursor(last.createdAt(), last.id()).encode(type, targetId);
        }
        return new LikeUsersPage(items, nextCursor, hasMore);
    }
}
