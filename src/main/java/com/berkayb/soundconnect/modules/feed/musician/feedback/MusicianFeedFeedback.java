package com.berkayb.soundconnect.modules.feed.musician.feedback;

import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedFeedbackAction;
import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedItemType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Getter
@NoArgsConstructor
@Table(name = "tbl_musician_feed_feedback",
        uniqueConstraints = @UniqueConstraint(name = "uk_musician_feed_feedback_scope",
                columnNames = {"viewer_user_id", "action", "scope_key"}),
        indexes = {
                @Index(name = "idx_musician_feed_feedback_viewer_action",
                        columnList = "viewer_user_id,action,created_at"),
                @Index(name = "idx_musician_feed_feedback_viewer_profile",
                        columnList = "viewer_user_id,author_profile_type,author_profile_id")
        })
public class MusicianFeedFeedback {
    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "viewer_user_id", nullable = false, updatable = false)
    private UUID viewerUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 24)
    private MusicianFeedFeedbackAction action;

    @Column(name = "scope_key", nullable = false, updatable = false, length = 320)
    private String scopeKey;

    @Column(name = "item_id", length = 256, updatable = false)
    private String itemId;

    @Enumerated(EnumType.STRING)
    @Column(name = "item_type", length = 48, updatable = false)
    private MusicianFeedItemType itemType;

    @Column(name = "delivery_id", updatable = false)
    private UUID deliveryId;

    @Column(name = "author_profile_type", length = 24, updatable = false)
    private String authorProfileType;

    @Column(name = "author_profile_id", updatable = false)
    private UUID authorProfileId;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static MusicianFeedFeedback item(
            UUID viewerUserId,
            MusicianFeedFeedbackAction action,
            String itemId,
            MusicianFeedItemType itemType,
            UUID deliveryId,
            String reason,
            Instant now
    ) {
        MusicianFeedFeedback value = new MusicianFeedFeedback();
        value.id = UUID.randomUUID();
        value.viewerUserId = viewerUserId;
        value.action = action;
        value.scopeKey = "ITEM:" + itemId;
        value.itemId = itemId;
        value.itemType = itemType;
        value.deliveryId = deliveryId;
        value.reason = reason;
        value.createdAt = now;
        value.updatedAt = now;
        return value;
    }

    static MusicianFeedFeedback item(UUID viewerUserId, MusicianFeedFeedbackAction action,
                                     String itemId, MusicianFeedItemType itemType,
                                     String reason, Instant now) {
        return item(viewerUserId, action, itemId, itemType, null, reason, now);
    }

    public static MusicianFeedFeedback mute(
            UUID viewerUserId,
            String authorProfileType,
            UUID authorProfileId,
            Instant now
    ) {
        MusicianFeedFeedback value = new MusicianFeedFeedback();
        value.id = UUID.randomUUID();
        value.viewerUserId = viewerUserId;
        value.action = MusicianFeedFeedbackAction.MUTE_AUTHOR;
        value.scopeKey = "AUTHOR:" + authorProfileType + ":" + authorProfileId;
        value.authorProfileType = authorProfileType;
        value.authorProfileId = authorProfileId;
        value.createdAt = now;
        value.updatedAt = now;
        return value;
    }

    public void updateReason(String value, Instant now) {
        reason = value;
        updatedAt = now;
    }
}
