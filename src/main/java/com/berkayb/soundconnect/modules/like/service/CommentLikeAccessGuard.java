package com.berkayb.soundconnect.modules.like.service;

import com.berkayb.soundconnect.modules.comment.repository.CommentRepository;
import com.berkayb.soundconnect.modules.comment.support.CommentTargetAccessGuard;
import com.berkayb.soundconnect.modules.engagement.enums.EngagementTargetType;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.modules.comment.support.CommentEntityFinder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.Objects;
import java.util.UUID;

/** Actual content first, then the same comment row lock used by soft deletion. */
@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class CommentLikeAccessGuard {
    private final CommentRepository comments;
    private final CommentTargetAccessGuard content;
    private final CommentEntityFinder finder;

    public void requireLikeable(UUID id,boolean mutation) {
        requireLikeable(null, id, mutation);
    }

    public void requireLikeable(UUID viewerId, UUID id, boolean mutation) {
        if(id==null) throw new SoundConnectException(ErrorType.INVALID_PARAMETER);
        var initial=comments.findCommentTarget(id).orElseThrow(this::notFound);
        EngagementTargetType type;
        try { type=EngagementTargetType.valueOf(initial.getTargetType()); }
        catch(RuntimeException malformed) { throw notFound(); }
        if(type==EngagementTargetType.COMMENT) throw notFound();
        content.requireReadable(viewerId, type, initial.getTargetId());
        var current=(mutation ? comments.lockComment(id) : comments.lockCommentForRead(id)).orElseThrow(this::notFound);
        finder.requireVisibleAuthor(viewerId, id, current.getUserId(), mutation);
        if(current.getDeleted() || !Objects.equals(current.getTargetType(),initial.getTargetType())
                || !Objects.equals(current.getTargetId(),initial.getTargetId())
                || !Objects.equals(current.getParentId(),initial.getParentId())) throw notFound();
        if(current.getParentId()!=null) {
            var parent=comments.findCommentTarget(current.getParentId()).orElseThrow(this::notFound);
            finder.requireVisibleAuthor(viewerId, parent.getId(), parent.getUserId(), false);
            // Existing replies on a soft-deleted root remain visible/likeable; nested or misbound rows do not.
            if(parent.getParentId()!=null || !Objects.equals(parent.getTargetType(),current.getTargetType())
                    || !Objects.equals(parent.getTargetId(),current.getTargetId())) throw notFound();
        }
    }

    private SoundConnectException notFound() { return new SoundConnectException(ErrorType.COMMENT_NOT_FOUND); }
}
