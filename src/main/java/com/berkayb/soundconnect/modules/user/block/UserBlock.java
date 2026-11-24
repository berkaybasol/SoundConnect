package com.berkayb.soundconnect.modules.user.block;

import com.berkayb.soundconnect.modules.user.entity.User;
import jakarta.persistence.*;
import lombok.EqualsAndHashCode;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/** Query mapping for viewer-aware pagination. Relationship writes belong to UserBlockService. */
@Entity
@Immutable
@Table(name = "tbl_user_block")
@IdClass(UserBlock.Key.class)
@Check(constraints = "blocker_id <> blocked_id")
public class UserBlock {
    @Id @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "blocker_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User blocker;

    @Id @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "blocked_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User blocked;

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz default now()")
    private Instant createdAt;

    @Column(name = "unblocked_at")
    private Instant unblockedAt;

    @EqualsAndHashCode
    public static class Key implements Serializable {
        UUID blocker;
        UUID blocked;
    }
}
