package com.berkayb.soundconnect.modules.follow.outbox;

import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "tbl_follow_notification_outbox",
        indexes = {
                @Index(
                        name = "idx_follow_outbox_due",
                        columnList = "status,next_attempt_at,created_at"
                ),
                @Index(
                        name = "idx_follow_outbox_lease",
                        columnList = "status,lease_until"
                ),
                @Index(
                        name = "idx_follow_outbox_published",
                        columnList = "status,published_at"
                )
        }
)
public class FollowNotificationOutbox {

    @Id
    @Column(name = "event_id", nullable = false, updatable = false, columnDefinition = "uuid")
    private UUID eventId;

    @Column(name = "recipient_id", nullable = false, updatable = false, columnDefinition = "uuid")
    private UUID recipientId;

    @Enumerated(EnumType.STRING)
    @Column(name = "notification_type", nullable = false, updatable = false, length = 64)
    private NotificationType notificationType;

    // Historical IDs deliberately have no relation/user/band FK: unfollow must not delete accepted work.
    @Column(name = "occurrence_id", nullable = false, updatable = false)
    private UUID occurrenceId;

    @Column(name = "follower_id", nullable = false, updatable = false)
    private UUID followerId;

    @Column(name = "band_id", updatable = false)
    private UUID bandId;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private FollowNotificationOutboxStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "lease_owner", length = 100)
    private String leaseOwner;

    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Column(name = "last_error_type", length = 200)
    private String lastErrorType;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

}
