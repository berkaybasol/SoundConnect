package com.berkayb.soundconnect.modules.overthinking.profileshare;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.Instant;
import java.util.UUID;

@Entity @Getter @NoArgsConstructor
@Table(name = "tbl_overthinking_profile_share", uniqueConstraints = @UniqueConstraint(
        name = "uq_overthinking_profile_share_owner_post", columnNames = {"owner_user_id", "source_post_id"}))
public class OverthinkingProfileShare {
    @Id @Column(name = "id", nullable = false) private UUID id;
    @Column(name = "owner_user_id", nullable = false) private UUID ownerUserId;
    @Column(name = "listener_profile_id", nullable = false) private UUID listenerProfileId;
    @Column(name = "source_post_id", nullable = false) private UUID sourcePostId;
    @Column(name = "note", length = 500) private String note;
    @Column(name = "published_at", nullable = false) private Instant publishedAt;

    public OverthinkingProfileShare(UUID ownerUserId, UUID listenerProfileId, UUID sourcePostId, String note, Instant now) {
        this.id = UUID.randomUUID(); this.ownerUserId = ownerUserId; this.listenerProfileId = listenerProfileId;
        this.sourcePostId = sourcePostId; this.note = note; this.publishedAt = now;
    }
}
