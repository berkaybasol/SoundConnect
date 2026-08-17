package com.berkayb.soundconnect.modules.collab.outbox;

import com.berkayb.soundconnect.modules.collab.event.CollabNotificationEvent;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "tbl_collab_notification_outbox",
        indexes = {
                @Index(
                        name = "idx_collab_notification_outbox_due",
                        columnList = "status,next_attempt_at,created_at"
                ),
                @Index(
                        name = "idx_collab_notification_outbox_lease",
                        columnList = "status,lease_until"
                ),
                @Index(
                        name = "idx_collab_notification_outbox_created",
                        columnList = "created_at"
                )
        }
)
public class CollabNotificationOutbox {

    @Id
    @Column(name = "event_id", nullable = false, updatable = false, columnDefinition = "uuid")
    private UUID eventId;

    @Column(name = "recipient_id", nullable = false, updatable = false, columnDefinition = "uuid")
    private UUID recipientId;

    @Enumerated(EnumType.STRING)
    @Column(name = "notification_type", nullable = false, updatable = false, length = 64)
    private NotificationType notificationType;

    @Column(name = "title", nullable = false, updatable = false, length = 160)
    private String title;

    @Column(name = "message", nullable = false, updatable = false, length = 1000)
    private String message;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, updatable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @Column(name = "email_force", nullable = false, updatable = false)
    private boolean emailForce;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private CollabNotificationOutboxStatus status;

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

    public static CollabNotificationOutbox pending(CollabNotificationEvent event, Instant now) {
        return CollabNotificationOutbox.builder()
                .eventId(event.eventId())
                .recipientId(event.recipientId())
                .notificationType(event.type())
                .title(event.title())
                .message(event.message())
                .payload(new LinkedHashMap<>(event.payload()))
                .emailForce(false)
                .occurredAt(event.occurredAt())
                .status(CollabNotificationOutboxStatus.PENDING)
                .attemptCount(0)
                .nextAttemptAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }
}
