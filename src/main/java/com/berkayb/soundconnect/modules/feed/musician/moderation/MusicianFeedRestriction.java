package com.berkayb.soundconnect.modules.feed.musician.moderation;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;

import java.time.Instant;
import java.util.UUID;

/** A report's independent feed restriction. Deliberately has no cascading report/user association. */
@Entity
@Getter
@NoArgsConstructor
@Table(name = "tbl_musician_feed_restriction", indexes = @Index(
        name = "idx_musician_feed_restriction_scope", columnList = "scope_key,active,report_id"))
public class MusicianFeedRestriction {
    @Id @Column(name = "report_id", nullable = false) private UUID reportId;
    @Column(name = "scope_key", nullable = false, length = 320) private String scopeKey;
    @Column(nullable = false) private boolean active;
    @Column(nullable = false) @ColumnDefault("false") private boolean orphaned;
    @Column(name = "applied_by_user_id", nullable = false) private UUID appliedByUserId;
    @Column(name = "applied_at", nullable = false) private Instant appliedAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
}
