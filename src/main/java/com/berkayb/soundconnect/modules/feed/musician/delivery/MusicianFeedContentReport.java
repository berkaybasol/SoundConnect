package com.berkayb.soundconnect.modules.feed.musician.delivery;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/** Generic moderation evidence for feed targets without a native report aggregate. */
@Entity
@Getter
@NoArgsConstructor
@Table(name = "tbl_musician_feed_content_report",
        uniqueConstraints = @UniqueConstraint(name = "uk_musician_feed_report_delivery",
                columnNames = {"viewer_user_id", "delivery_id"}),
        indexes = @Index(name = "idx_musician_feed_report_queue",
                columnList = "status,reported_at"))
public class MusicianFeedContentReport {
    @Id private UUID id;
    @Column(name = "viewer_user_id", nullable = false) private UUID viewerUserId;
    @Column(name = "delivery_id") private UUID deliveryId;
    @Column(name = "item_id", nullable = false, length = 256) private String itemId;
    @Column(name = "item_type", nullable = false, length = 48) private String itemType;
    @Column(name = "target_type", nullable = false, length = 48) private String targetType;
    @Column(name = "target_id", nullable = false) private UUID targetId;
    @Column(name = "reason", length = 500) private String reason;
    @Column(name = "evidence_json", nullable = false, columnDefinition = "jsonb") private String evidenceJson;
    @Column(nullable = false, length = 24) private String status;
    @Column(name = "reported_at", nullable = false) private Instant reportedAt;
}
