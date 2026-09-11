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
            case EVENT_POST -> readableEventPost(id);
            case TABLE_GROUP_POST -> readableTablePost(id);
            case OVERTHINKING_PROFILE_SHARE -> readableOverthinkingProfileShare(id);
            case OVERTHINKING -> repository.lockPost(id).isPresent();
            case MEDIA -> readableMedia(id);
            // COMMENT is a like target only, never another commentable content level.
            case COMMENT -> false;
        };
        if (!visible) throw new SoundConnectException(ErrorType.ENGAGEMENT_NOT_FOUND);
    }

    private boolean readableEventPost(UUID id) {
        var owner = repository.eventPostOwner(id).orElse(null);
        if (owner == null || owner.getUserId() == null || owner.getEventId() == null) return false;
        // Account -> listener privacy -> event -> publication matches audience writes/removal.
        // Every value is read under database fences; stale OSIV entities cannot expose ghost posts.
        if (repository.lockActivePostAuthor(owner.getUserId()).isEmpty()
                || !repository.eligibleListenerPostAuthor(owner.getUserId())
                || !repository.lockListenerVisibility(owner.getUserId(), false).orElse(false)
                || repository.lockPublicEvent(owner.getEventId()).isEmpty()) return false;
        return repository.lockPublishedEventPost(id, owner.getUserId(), owner.getEventId()).isPresent();
    }

    private boolean readableTablePost(UUID id) {
        var owner = repository.tablePostOwner(id).orElse(null);
        if (owner == null || owner.getUserId() == null || owner.getTableGroupId() == null) return false;
        // Match publication writes: account -> privacy -> aggregate -> exact share.
        // Capture expiry time after the aggregate lock; waiting may cross expiry.
        if (repository.lockActivePostAuthor(owner.getUserId()).isEmpty()
                || !repository.eligibleListenerPostAuthor(owner.getUserId())
                || !repository.lockListenerVisibility(owner.getUserId(), false).orElse(false)
                || repository.lockTableGroup(owner.getTableGroupId()).isEmpty()) return false;
        repository.freezeEndedTablePost(owner.getTableGroupId());
        return repository.lockPublishedTablePost(id, owner.getUserId(), owner.getTableGroupId(), java.time.Instant.now()).isPresent();
    }

    private boolean readableOverthinkingProfileShare(UUID id) {
        var owner = repository.overthinkingProfileShareOwner(id).orElse(null);
        if (owner == null || owner.getUserId() == null || owner.getSourcePostId() == null) return false;
        // Match publication writes/removal: account -> listener visibility ->
        // source -> exact publication. This prevents a source/profile deletion
        // from committing between validation and an engagement insert.
        if (repository.lockActivePostAuthor(owner.getUserId()).isEmpty()
                || !repository.eligibleListenerPostAuthor(owner.getUserId())
                || !repository.lockListenerVisibility(owner.getUserId(), false).orElse(false)
                || repository.lockPost(owner.getSourcePostId()).isEmpty()) return false;
        return repository.lockPublishedOverthinkingProfileShare(
                id, owner.getUserId(), owner.getSourcePostId()).isPresent();
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
