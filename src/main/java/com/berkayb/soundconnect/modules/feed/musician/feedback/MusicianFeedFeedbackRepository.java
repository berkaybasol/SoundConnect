package com.berkayb.soundconnect.modules.feed.musician.feedback;

import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedFeedbackAction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface MusicianFeedFeedbackRepository extends JpaRepository<MusicianFeedFeedback, UUID> {
    Optional<MusicianFeedFeedback> findByViewerUserIdAndActionAndScopeKey(
            UUID viewerUserId, MusicianFeedFeedbackAction action, String scopeKey);
    long deleteByViewerUserIdAndActionAndScopeKey(
            UUID viewerUserId, MusicianFeedFeedbackAction action, String scopeKey);
}
