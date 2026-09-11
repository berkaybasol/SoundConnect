package com.berkayb.soundconnect.modules.feed.musician.delivery;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/** Hibernate-first schema mirror; writes are atomic JDBC upserts in the delivery service. */
@Entity
@Getter
@NoArgsConstructor
@Table(name = "tbl_musician_feed_delivery",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_musician_feed_delivery_item",
                        columnNames = {"viewer_user_id", "feed_session_id", "item_id"}),
                @UniqueConstraint(name = "uk_musician_feed_delivery_position",
                        columnNames = {"viewer_user_id", "feed_session_id", "absolute_position"})
        }, indexes = {
        @Index(name = "idx_musician_feed_delivery_session",
                columnList = "viewer_user_id,feed_session_id,expires_at"),
        @Index(name = "idx_musician_feed_delivery_campaign",
                columnList = "viewer_user_id,campaign_id,delivered_at")
})
public class MusicianFeedDelivery {
    @Id private UUID id;
    @Column(name = "viewer_user_id", nullable = false) private UUID viewerUserId;
    @Column(name = "feed_session_id", nullable = false) private UUID feedSessionId;
    @Column(name = "item_id", nullable = false, length = 256) private String itemId;
    @Column(name = "item_type", nullable = false, length = 48) private String itemType;
    @Column(name = "feed_lane", nullable = false, length = 32) private String feedLane;
    @Column(name = "target_type", nullable = false, length = 48) private String targetType;
    @Column(name = "target_id", nullable = false) private UUID targetId;
    @Column(name = "author_profile_type", length = 24) private String authorProfileType;
    @Column(name = "author_profile_id") private UUID authorProfileId;
    @Column(name = "reason_code", length = 64) private String reasonCode;
    @Column(name = "feedback_capabilities", nullable = false, length = 160) private String feedbackCapabilities;
    @Column(name = "schema_version", nullable = false) private int schemaVersion;
    @Column(name = "algorithm_version", nullable = false, length = 64) private String algorithmVersion;
    @Column(name = "absolute_position", nullable = false) private long absolutePosition;
    @Column(name = "campaign_id") private UUID campaignId;
    @Column(name = "evidence_json", nullable = false, columnDefinition = "jsonb") private String evidenceJson;
    @Column(name = "delivered_at", nullable = false) private Instant deliveredAt;
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;
    @Column(name = "purge_after", nullable = false) private Instant purgeAfter;
}
