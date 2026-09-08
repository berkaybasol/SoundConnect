package com.berkayb.soundconnect.modules.notification.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/** Minimal durable replay fence; deliberately contains no notification content. */
@Entity
@Table(name = "tbl_notification_receipt")
@Getter
@NoArgsConstructor
public class NotificationReceipt {
    @Id
    @Column(name = "source_event_id", nullable = false, updatable = false, columnDefinition = "uuid")
    private UUID sourceEventId;

    @Column(name = "recipient_id", nullable = false, updatable = false, columnDefinition = "uuid")
    private UUID recipientId;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;
}
