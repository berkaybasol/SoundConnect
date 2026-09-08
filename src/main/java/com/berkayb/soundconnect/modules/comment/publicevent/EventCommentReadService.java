package com.berkayb.soundconnect.modules.comment.publicevent;

import com.berkayb.soundconnect.modules.comment.dto.response.CommentReplyResponseDto;
import com.berkayb.soundconnect.modules.comment.dto.response.CommentResponseDto;
import com.berkayb.soundconnect.modules.comment.service.CommentService;
import com.berkayb.soundconnect.modules.engagement.enums.EngagementTargetType;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
// Existing author-privacy resolution takes shared row locks, which PostgreSQL forbids in read-only transactions.
@Transactional(isolation = Isolation.REPEATABLE_READ)
public class EventCommentReadService {
    private final EventCommentReadRepository repository;
    private final CommentService comments;

    public Page<CommentResponseDto> getComments(UUID viewerId, UUID eventId, int page, int size) {
        PageRequest pageable = validateAndPage(eventId, page, size);
        requirePublicEvent(eventId);
        return comments.getComments(viewerId, EngagementTargetType.EVENT, eventId, pageable);
    }

    public Page<CommentReplyResponseDto> getReplies(UUID viewerId, UUID eventId, UUID commentId, int page, int size) {
        PageRequest pageable = validateAndPage(eventId, page, size);
        if (commentId == null) throw new SoundConnectException(ErrorType.INVALID_PARAMETER);
        requirePublicEvent(eventId);
        if (!repository.existsEventRootComment(eventId, commentId)) {
            throw new SoundConnectException(ErrorType.COMMENT_NOT_FOUND);
        }
        // A soft-deleted root keeps its existing replies readable. Shared mapping still masks deleted text.
        return comments.getReplies(viewerId, commentId, pageable);
    }

    private PageRequest validateAndPage(UUID eventId, int page, int size) {
        if (eventId == null || page < 0 || page > 1000 || size < 1 || size > 50) {
            throw new SoundConnectException(ErrorType.INVALID_PARAMETER);
        }
        return PageRequest.of(page, size);
    }

    private void requirePublicEvent(UUID eventId) {
        if (!repository.existsPublicEvent(eventId)) {
            throw new SoundConnectException(ErrorType.EVENT_NOT_FOUND);
        }
    }
}
