package com.berkayb.soundconnect.modules.comment.support;

import com.berkayb.soundconnect.modules.comment.repository.CommentTargetAccessRepository;
import com.berkayb.soundconnect.modules.engagement.enums.EngagementTargetType;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

/** Comments never provide a back door to a target hidden by its existing public read policy. */
@Component
@RequiredArgsConstructor
public class CommentTargetAccessGuard {
    private final CommentTargetAccessRepository repository;

    @Transactional(propagation = Propagation.MANDATORY)
    public void requireReadable(EngagementTargetType type, UUID id) {
        if (type == null || id == null) throw new SoundConnectException(ErrorType.INVALID_PARAMETER);
        boolean visible = switch (type) {
            case EVENT -> repository.lockPublicEvent(id).isPresent();
            case OVERTHINKING -> repository.lockPost(id).isPresent();
            case MEDIA -> readableMedia(id);
            // COMMENT is a like target only, never another commentable content level.
            case COMMENT -> false;
        };
        if (!visible) throw new SoundConnectException(ErrorType.ENGAGEMENT_NOT_FOUND);
    }

    private boolean readableMedia(UUID id) {
        var owner = repository.mediaOwner(id).orElse(null);
        if (owner == null || owner.getOwnerId() == null || owner.getOwnerType() == null) return false;
        // Visibility before media: same ordering as profile media readers/assignment.
        boolean listener = "LISTENER_PROFILE".equals(owner.getOwnerType());
        if (listener || "USER".equals(owner.getOwnerType())) {
            if (!repository.lockListenerVisibility(owner.getOwnerId(), listener).orElse(!listener)) return false;
        }
        // Recheck owner after locking: ownership cannot change between the privacy check and access.
        return repository.lockPublicMedia(id, owner.getOwnerType(), owner.getOwnerId()).isPresent();
    }
}
