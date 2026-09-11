package com.berkayb.soundconnect.modules.feed.musician.feedback;

import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedFeedbackAction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MusicianFeedFeedbackRepository extends JpaRepository<MusicianFeedFeedback, UUID> {
    List<MusicianFeedFeedback> findAllByViewerUserId(UUID viewerUserId);
    Optional<MusicianFeedFeedback> findByViewerUserIdAndActionAndScopeKey(
            UUID viewerUserId, MusicianFeedFeedbackAction action, String scopeKey);
    long countByViewerUserId(UUID viewerUserId);
    long deleteByViewerUserIdAndActionAndScopeKey(
            UUID viewerUserId, MusicianFeedFeedbackAction action, String scopeKey);
}
