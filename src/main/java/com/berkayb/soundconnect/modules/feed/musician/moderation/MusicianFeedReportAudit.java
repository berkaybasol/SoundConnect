package com.berkayb.soundconnect.modules.feed.musician.moderation;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.Instant;
import java.util.UUID;

/** Append-only application audit; actor UUIDs do not block account erasure. */
@Entity
@Getter
@NoArgsConstructor
@Table(name = "tbl_musician_feed_report_audit", uniqueConstraints = {
        @UniqueConstraint(name = "uk_musician_feed_report_audit_request", columnNames = {"report_id", "client_request_id"}),
        @UniqueConstraint(name = "uk_musician_feed_report_audit_version", columnNames = {"report_id", "resulting_version"})})
public class MusicianFeedReportAudit {
    @Id private UUID id;
    @Column(name = "report_id", nullable = false, updatable = false) private UUID reportId;
    @Column(name = "client_request_id", nullable = false, updatable = false) private UUID clientRequestId;
    @Column(name = "request_hash", nullable = false, updatable = false, length = 64) private String requestHash;
    @Column(name = "resulting_version", nullable = false, updatable = false) private long resultingVersion;
    @Column(nullable = false, updatable = false, length = 24) private String decision;
    @Column(name = "previous_status", nullable = false, updatable = false, length = 24) private String previousStatus;
    @Column(nullable = false, updatable = false, length = 24) private String status;
    @Column(name = "actor_user_id", nullable = false, updatable = false) private UUID actorUserId;
    @Column(name = "occurred_at", nullable = false, updatable = false) private Instant occurredAt;
    @Column(name = "resolution_note", nullable = false, updatable = false, length = 500) private String resolutionNote;
}
