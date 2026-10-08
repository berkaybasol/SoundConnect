package com.berkayb.soundconnect.modules.feed.musician.moderation;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.Instant;
import java.util.UUID;

/** Retains the administrator's orphan-restoration decision without retaining the reporter's data. */
@Entity
@Getter
@NoArgsConstructor
@Table(name = "tbl_musician_feed_restriction_restore_audit", uniqueConstraints = {
        @UniqueConstraint(name = "uk_musician_feed_restriction_restore_request", columnNames = {"report_id", "client_request_id"}),
        @UniqueConstraint(name = "uk_musician_feed_restriction_restore_report", columnNames = "report_id")
})
public class MusicianFeedRestrictionRestoreAudit {
    @Id private UUID id;
    @Column(name = "report_id", nullable = false) private UUID reportId;
    @Column(name = "client_request_id", nullable = false) private UUID clientRequestId;
    @Column(name = "request_hash", nullable = false, length = 64) private String requestHash;
    @Column(name = "actor_user_id", nullable = false) private UUID actorUserId;
    @Column(name = "resolution_note", nullable = false, length = 500) private String resolutionNote;
    @Column(name = "restored_at", nullable = false) private Instant restoredAt;
}
